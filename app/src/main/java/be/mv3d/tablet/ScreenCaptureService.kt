package be.mv3d.tablet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Eigen schermdeling ZONDER tunnel. cloudflared kan op Android geen DNS doen, dus we
 * relayen de beelden rechtstreeks via mv3d.be (waar de tablet toch al mee praat):
 *  1. MediaProjection (toestemming via ProjectionRequestActivity) → scherm → JPEG,
 *  2. elke ~350ms POST het laatste frame naar /api/machines/screen-frame,
 *  3. het antwoord bevat de wachtende tik/veeg-acties → uitvoeren via RemoteInputService.
 * Het portaal toont het frame (GET /screen-frame) en zet input in de wachtrij (/screen-input).
 */
class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val prefs by lazy { Prefs(this) }
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var handlerThread: HandlerThread? = null

    companion object {
        const val CHANNEL = "mv3d_screen"
        const val MAX_WIDTH = 820
        const val FRAME_MS = 350L
        const val EXTRA_RESULT = "result_code"
        const val EXTRA_DATA = "result_data"
        @Volatile var status: String = "uit"
        @Volatile var latestJpeg: ByteArray? = null
        @Volatile var frameW = 0
        @Volatile var frameH = 0
        @Volatile var active = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat("Scherm delen wordt gestart…")
        if (active) return START_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode == 0 || data == null) { status = "geen toestemming"; stopSelf(); return START_NOT_STICKY }
        active = true
        scope.launch { run(resultCode, data) }
        return START_STICKY
    }

    private suspend fun run(resultCode: Int, data: Intent) {
        val code = prefs.code(); val srv = prefs.server()
        val api = if (code.isNotBlank()) Api(srv, code) else null
        fun report(s: String) { status = s; try { updateNotification(s) } catch (_: Exception) {}; api?.screen(s) }
        try {
            report("opname aanvragen…")
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
                ?: throw RuntimeException("getMediaProjection gaf null (toestemming?)")
            projection = proj

            val ht = HandlerThread("mv3d-capture").also { it.start() }
            handlerThread = ht
            val handler = Handler(ht.looper)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)

            val (realW, realH, dpi) = screenSize()
            val scale = if (realW > MAX_WIDTH) MAX_WIDTH.toFloat() / realW else 1f
            val capW = (realW * scale).toInt().coerceAtLeast(2) and 1.inv()
            val capH = (realH * scale).toInt().coerceAtLeast(2) and 1.inv()
            frameW = capW; frameH = capH
            report("scherm opnemen ${capW}x${capH}…")

            val ir = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            reader = ir
            ir.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * capW
                    val bmpW = capW + rowPadding / plane.pixelStride
                    var bmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    if (bmpW != capW) { val c = Bitmap.createBitmap(bmp, 0, 0, capW, capH); bmp.recycle(); bmp = c }
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 50, bos)
                    latestJpeg = bos.toByteArray()
                    bmp.recycle()
                } catch (_: Exception) {
                } finally { image.close() }
            }, handler)

            vdisplay = proj.createVirtualDisplay(
                "mv3d-cap", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface, null, handler
            )

            report("beeld streamen via mv3d.be…")
            var announced = false
            var lastReport = 0L
            while (scope.isActive && active && api != null) {
                val jpg = latestJpeg
                if (jpg != null) {
                    try {
                        val inputs = api.screenFrame(jpg)
                        for (i in 0 until inputs.length()) applyInput(inputs.getJSONObject(i))
                        if (!announced) { announced = true; report("actief · beeld beschikbaar" + if (RemoteInputService.enabled) " · bediening aan" else " · alleen kijken") }
                    } catch (e: Exception) {
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 5000) { lastReport = now; report("streamfout: ${e.message}") }
                    }
                }
                delay(FRAME_MS)
            }
        } catch (e: Exception) {
            report("fout: ${e.javaClass.simpleName}: ${e.message}")
            cleanup(); stopSelf()
        }
    }

    private fun applyInput(o: JSONObject) {
        val svc = RemoteInputService.instance ?: return
        when (o.optString("type")) {
            "tap" -> svc.tap(o.optDouble("x").toFloat(), o.optDouble("y").toFloat())
            "swipe" -> svc.swipe(o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
                o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(), o.optLong("dur", 200))
            "back" -> svc.globalBack()
            "home" -> svc.globalHome()
            "recents" -> svc.globalRecents()
        }
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dm.densityDpi)
        } else {
            val d = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(d)
            Triple(d.widthPixels, d.heightPixels, d.densityDpi)
        }
    }

    private fun cleanup() {
        active = false
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        latestJpeg = null
    }

    override fun onDestroy() {
        cleanup(); status = "uit"
        scope.launch { val code = prefs.code(); if (code.isNotBlank()) try { Api(prefs.server(), code).screen("uit") } catch (_: Exception) {} }
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(3, n)
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CHANNEL, "MV3D scherm delen", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("MV3D — scherm delen").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(3, notification(text))
    }
}
