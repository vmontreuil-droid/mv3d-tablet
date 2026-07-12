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
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.security.SecureRandom

/**
 * Eigen schermdeling — vervangt droidVNC-NG + noVNC volledig.
 *  1. MediaProjection (toestemming al gegeven via ProjectionRequestActivity),
 *  2. schermbeeld → VirtualDisplay → ImageReader → JPEG (geschaald voor bandbreedte),
 *  3. ingebedde HTTP-server (NanoHTTPD) serveert /shot.jpg + /meta en aanvaardt POST /input,
 *  4. cloudflared (bijgeleverde arm64-binary) opent een publieke tunnel naar die server,
 *  5. tunnel-URL + sessietoken worden gemeld via POST /api/machines/tunnel.
 * Het portaal toont gewoon een <img> die /shot.jpg pollt en POST't tik/veeg naar /input.
 */
class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val prefs by lazy { Prefs(this) }
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var server: CaptureServer? = null
    private var cloudflared: Process? = null
    private var handlerThread: HandlerThread? = null

    companion object {
        const val CHANNEL = "mv3d_screen"
        const val WEB_PORT = 6080
        const val MAX_WIDTH = 820           // geschaalde opname-breedte (bandbreedte vs. leesbaarheid)
        const val EXTRA_RESULT = "result_code"
        const val EXTRA_DATA = "result_data"
        @Volatile var tunnelUrl: String? = null
        @Volatile var token: String = ""
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
        token = randomToken()
        scope.launch { start(resultCode, data) }
        return START_STICKY
    }

    private suspend fun start(resultCode: Int, data: Intent) {
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
            val capW = (realW * scale).toInt().coerceAtLeast(2) and 1.inv()  // even
            val capH = (realH * scale).toInt().coerceAtLeast(2) and 1.inv()
            frameW = capW; frameH = capH
            report("scherm opnemen ${capW}x${capH}…")

            val ir = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            reader = ir
            ir.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * capW
                    val bmpW = capW + rowPadding / pixelStride
                    var bmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    if (bmpW != capW) { val cropped = Bitmap.createBitmap(bmp, 0, 0, capW, capH); bmp.recycle(); bmp = cropped }
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 55, bos)
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

            server = CaptureServer(WEB_PORT).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            report("tunnel opzetten (cloudflared)…")

            val url = startCloudflared()
            tunnelUrl = url
            report("tunnel melden…")
            api?.tunnel(url, token)
            report("actief · beeld beschikbaar")
        } catch (e: Exception) {
            report("fout: ${e.javaClass.simpleName}: ${e.message}")
            cleanup(); stopSelf()
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

    /**
     * cloudflared starten (native lib) en de publieke URL uit de output plukken.
     * De output wordt op een aparte thread gelezen (readLine() kan blokkeren), zodat de
     * 40s-deadline altijd afdwingbaar is. Bij mislukking gaat cloudflared's laatste output
     * mee in de fout, zodat we op afstand zien WAAROM (rate-limit, netwerk, …).
     */
    private fun startCloudflared(): String {
        val bin = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!bin.exists()) throw RuntimeException("cloudflared ontbreekt (libcloudflared.so)")
        val proc = ProcessBuilder(
            bin.absolutePath, "tunnel", "--no-autoupdate", "--url", "http://localhost:$WEB_PORT"
        ).redirectErrorStream(true).start()
        cloudflared = proc
        val rx = Regex("https://[a-z0-9]+(?:-[a-z0-9]+)+\\.trycloudflare\\.com")
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val tail = StringBuilder()
        val t = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    synchronized(tail) { tail.append(l).append('\n'); if (tail.length > 1500) tail.delete(0, tail.length - 1500) }
                    val m = rx.find(l)
                    if (m != null) { found.set(m.value); break }
                }
            } catch (_: Exception) {}
        }
        t.isDaemon = true; t.start()
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            found.get()?.let { return it }
            if (!proc.isAlive) break
            Thread.sleep(300)
        }
        found.get()?.let { return it }
        val out = synchronized(tail) { tail.toString().trim() }.takeLast(280)
        throw RuntimeException("geen tunnel in 40s. cloudflared: ${out.ifBlank { "(geen output — binary/arch?)" }}")
    }

    private fun randomToken(): String {
        val a = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = SecureRandom()
        return (1..20).map { a[rnd.nextInt(a.length)] }.joinToString("")
    }

    private fun cleanup() {
        active = false
        try { server?.stop() } catch (_: Exception) {}
        try { cloudflared?.destroy() } catch (_: Exception) {}
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        latestJpeg = null; tunnelUrl = null
    }

    override fun onDestroy() {
        cleanup(); status = "uit"
        scope.launch { val code = prefs.code(); if (code.isNotBlank()) try { Api(prefs.server(), code).tunnel(null) } catch (_: Exception) {} }
        super.onDestroy()
    }

    // ── ingebedde HTTP-server ──
    private inner class CaptureServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
            // token-check (behalve /meta, dat is onschuldig)
            val t = session.parameters["t"]?.firstOrNull()
            return when {
                uri.startsWith("/shot") -> {
                    if (t != token) return cors(newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "nope"))
                    val jpg = latestJpeg ?: return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
                    cors(newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpg), jpg.size.toLong())).apply {
                        addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    }
                }
                uri.startsWith("/meta") -> cors(newFixedLengthResponse(Response.Status.OK, "application/json",
                    JSONObject().put("w", frameW).put("h", frameH).put("input", RemoteInputService.enabled).toString()))
                uri.startsWith("/input") -> {
                    if (t != token) return cors(newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "nope"))
                    handleInput(session)
                }
                else -> cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "MV3D screen"))
            }
        }

        private fun handleInput(session: IHTTPSession): Response {
            return try {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val body = map["postData"] ?: "{}"
                val o = JSONObject(body)
                val svc = RemoteInputService.instance
                    ?: return cors(newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":false,"reason":"accessibility_off"}"""))
                when (o.optString("type")) {
                    "tap" -> svc.tap(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
                    "swipe" -> svc.swipe(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                        o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat(), o.optLong("dur", 200))
                    "back" -> svc.globalBack()
                    "home" -> svc.globalHome()
                    "recents" -> svc.globalRecents()
                }
                cors(newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}"""))
            } catch (e: Exception) {
                cors(newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":false,"reason":"${e.message}"}"""))
            }
        }

        private fun cors(r: Response): Response {
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "Content-Type")
            return r
        }
    }

    // ── notificatie ──
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
