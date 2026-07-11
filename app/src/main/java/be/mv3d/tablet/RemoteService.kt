package be.mv3d.tablet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Fase 2 — scherm overnemen. Orkestreert de bediening-op-afstand:
 *   1. start **droidVNC-NG** (aparte app: scherm via MediaProjection, input via Accessibility),
 *   2. start **cloudflared** (bijgeleverde arm64-binary in assets) als publieke HTTPS-tunnel
 *      naar de lokale VNC/noVNC-poort,
 *   3. plukt de trycloudflare-URL uit de output en meldt ze via POST /api/machines/tunnel,
 *   4. bij stoppen: cloudflared doden, tunnel wissen (null), droidVNC-NG stoppen.
 *
 * Verificatiepunten op toestel (kan hier niet gecompileerd/getest worden):
 *  - droidVNC-NG intent-actie/extra-namen (hieronder volgens hun publieke intent-API),
 *  - de lokale poort die noVNC-websocket serveert (droidVNC-NG: standaard 5900 RFB;
 *    zet LOCAL_PORT op de poort die je in de noVNC-viewer verwacht).
 */
class RemoteService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val prefs by lazy { Prefs(this) }
    private var cloudflared: Process? = null
    private var bridge: NoVncBridge? = null

    companion object {
        const val CHANNEL = "mv3d_remote"
        const val VNC_PORT = 5900   // droidVNC-NG RFB-poort
        // De MV3D noVNC-viewer laadt `${tunnel}/vnc.html?...&path=websockify`. De tunnel moet dus een
        // noVNC-HTTP + websocket-brug serveren (websockify --web=noVNC of een ingebedde bridge) die
        // naar VNC_PORT brugt. cloudflared wijst daarom naar WEB_PORT, NIET naar de rauwe RFB-poort.
        const val WEB_PORT = 6080
        // droidVNC-NG publieke intent-API:
        const val VNC_PKG = "net.christianbeier.droidvnc_ng"
        const val VNC_SVC = "net.christianbeier.droidvnc_ng.MainService"
        const val VNC_START = "net.christianbeier.droidvnc_ng.ACTION_START"
        const val VNC_STOP = "net.christianbeier.droidvnc_ng.ACTION_STOP"
        const val VNC_EXTRA_PORT = "net.christianbeier.droidvnc_ng.EXTRA_PORT"
        const val VNC_EXTRA_PASSWORD = "net.christianbeier.droidvnc_ng.EXTRA_PASSWORD"
        @Volatile var tunnelUrl: String? = null
        @Volatile var status: String = "uit"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, notification("Scherm delen wordt gestart…"))
        scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val pw = randomPassword() // per sessie; via droidVNC-NG (RFB) + gemeld aan MV3D voor auto-login
        startDroidVnc(pw)
        try {
            bridge = NoVncBridge(this, WEB_PORT, VNC_PORT).also { it.startBridge() } // noVNC + ws→tcp op WEB_PORT
            val url = startCloudflared()
            tunnelUrl = url; status = "actief · $url"
            updateNotification("Scherm delen actief")
            val code = prefs.code(); val server = prefs.server()
            if (code.isNotBlank()) Api(server, code).tunnel(url, pw)
        } catch (e: Exception) {
            status = "fout: ${e.message}"; updateNotification(status)
        }
    }

    /** droidVNC-NG starten via zijn intent-API (aparte, geïnstalleerde app), met per-sessie wachtwoord. */
    private fun startDroidVnc(password: String) {
        try {
            val i = Intent(VNC_START).apply {
                component = ComponentName(VNC_PKG, VNC_SVC)
                putExtra(VNC_EXTRA_PORT, VNC_PORT)
                putExtra(VNC_EXTRA_PASSWORD, password)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            status = "droidVNC-NG niet gestart (geïnstalleerd?): ${e.message}"
        }
    }

    /** RFB VNC-auth gebruikt max 8 tekens; genereer een veilig 8-teken wachtwoord. */
    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun stopDroidVnc() {
        try { startService(Intent(VNC_STOP).apply { component = ComponentName(VNC_PKG, VNC_SVC) }) } catch (_: Exception) {}
    }

    /** cloudflared starten (meegeleverd als native lib) en de publieke URL uit de output plukken. */
    private fun startCloudflared(): String {
        // ligt in de uitvoerbare native-lib-map: jniLibs/arm64-v8a/libcloudflared.so
        val bin = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!bin.exists()) throw RuntimeException("cloudflared ontbreekt — plaats libcloudflared.so in jniLibs/arm64-v8a/")
        val proc = ProcessBuilder(
            bin.absolutePath, "tunnel", "--no-autoupdate", "--url", "http://localhost:$WEB_PORT"
        ).redirectErrorStream(true).start()
        cloudflared = proc

        val rx = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val deadline = System.currentTimeMillis() + 30_000
        var line: String?
        while (reader.readLine().also { line = it } != null && System.currentTimeMillis() < deadline) {
            rx.find(line ?: "")?.let { return it.value }
        }
        throw RuntimeException("geen tunnel-URL ontvangen (cloudflared-binary aanwezig in assets?)")
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CHANNEL, "MV3D remote", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL).setContentTitle("MV3D — scherm delen").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(2, notification(text))
    }

    override fun onDestroy() {
        try { cloudflared?.destroy() } catch (_: Exception) {}
        try { bridge?.stop() } catch (_: Exception) {}
        stopDroidVnc()
        tunnelUrl = null; status = "uit"
        scope.launch { val code = prefs.code(); if (code.isNotBlank()) try { Api(prefs.server(), code).tunnel(null) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
