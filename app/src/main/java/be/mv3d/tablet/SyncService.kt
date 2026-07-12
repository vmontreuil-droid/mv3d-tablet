package be.mv3d.tablet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service die periodiek /api/machines/sync pollt: bestanden binnenhalen naar de
 * gekozen map (SAF), commando's uitvoeren, heartbeat + GPS + mappenlijst sturen en bevestigen.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val prefs by lazy { Prefs(this) }

    companion object {
        const val CHANNEL = "mv3d_sync"
        const val INTERVAL_MS = 15_000L
        @Volatile var running = false; private set
        @Volatile var lastStatus: String = "—"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("MV3D actief"))
        if (!running) { running = true; loop() }
        return START_STICKY
    }

    private fun loop() = scope.launch {
        while (isActive) {
            try { tick() } catch (e: Exception) { lastStatus = "fout: ${e.message}" }
            delay(INTERVAL_MS)
        }
    }

    private suspend fun tick() {
        val code = prefs.code(); val server = prefs.server(); val treeStr = prefs.tree()
        if (code.isBlank() || treeStr.isBlank()) { lastStatus = "niet gekoppeld"; return }
        val api = Api(server, code)
        val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeStr)) ?: run { lastStatus = "map ongeldig"; return }

        val listing = JSONArray().apply { tree.listFiles().forEach { f -> put(JSONObject().put("name", f.name ?: "").put("size", f.length()).put("dir", f.isDirectory)) } }
        val loc = lastLocation()
        val res = api.sync(listing, loc?.first, loc?.second, loc?.third)

        val done = ArrayList<String>()
        for (f in res.files) {
            try { writeIntoTree(tree, f.subfolder, f.name, api.download(f.url)); done.add(f.id) }
            catch (e: Exception) { lastStatus = "download ${f.name}: ${e.message}" }
        }

        val results = ArrayList<Pair<String, String?>>()
        for (c in res.commands) {
            try {
                when (c.kind) {
                    "delete" -> { findInTree(tree, c.path)?.delete(); results.add(c.id to null) }
                    "move" -> { val src = findInTree(tree, c.path); if (src != null && c.newPath != null) { writeIntoTree(tree, null, c.newPath.substringAfterLast('/'), readDoc(src)); src.delete() }; results.add(c.id to null) }
                    "push" -> { if (c.downloadUrl != null) writeIntoTree(tree, null, c.fileName ?: "bestand", api.download(c.downloadUrl)); results.add(c.id to null) }
                    "pull" -> { val src = findInTree(tree, c.path); if (src != null && c.uploadUrl != null) api.upload(c.uploadUrl, readDoc(src)); results.add(c.id to null) }
                    "screen" -> { // scherm-delen op afstand aan/uit (baas stuurt het vanaf het portaal)
                        if (c.path == "on") requestScreenShare() else stopService(Intent(this, ScreenCaptureService::class.java))
                        results.add(c.id to null)
                    }
                    else -> results.add(c.id to "onbekend commando ${c.kind}")
                }
            } catch (e: Exception) { results.add(c.id to (e.message ?: "fout")) }
        }

        if (done.isNotEmpty() || results.isNotEmpty()) api.confirm(done, results)
        lastStatus = "ok · ${done.size} bestand(en) · ${res.commands.size} cmd" + (res.guidance?.let { " · $it" } ?: "")
    }

    /**
     * 'screen on' vanaf het portaal → MediaProjection-toestemming vragen. Android verplicht dat dit
     * vanuit een Activity komt, dus we tonen een volledig-scherm-melding ("De baas wil je scherm
     * zien — tik om te delen"). Op veel toestellen opent die meteen; anders tikt de machinist één keer.
     */
    private fun requestScreenShare() {
        if (ScreenCaptureService.active) return
        val open = Intent(this, ProjectionRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { startActivity(open) } catch (_: Exception) {}
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, open, flags)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel("mv3d_screen_req", "MV3D scherm-verzoek", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "mv3d_screen_req")
            .setContentTitle("MV3D — scherm delen")
            .setContentText("De baas wil je scherm zien. Tik om te delen.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        nm.notify(9, n)
    }

    // ── SAF helpers ──
    private fun writeIntoTree(root: DocumentFile, subfolder: String?, name: String, bytes: ByteArray) {
        var dir = root
        subfolder?.split('/')?.filter { it.isNotBlank() }?.forEach { part ->
            dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: dir.createDirectory(part) ?: dir
        }
        dir.findFile(name)?.delete()
        val doc = dir.createFile("application/octet-stream", name) ?: return
        contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) }
    }

    private fun findInTree(root: DocumentFile, path: String?): DocumentFile? {
        if (path.isNullOrBlank()) return null
        var cur: DocumentFile? = root
        path.split('/').filter { it.isNotBlank() }.forEach { part -> cur = cur?.findFile(part) }
        return cur
    }

    private fun readDoc(doc: DocumentFile): ByteArray =
        contentResolver.openInputStream(doc.uri)?.use { it.readBytes() } ?: ByteArray(0)

    private suspend fun lastLocation(): Triple<Double, Double, Float>? = try {
        val loc = LocationServices.getFusedLocationProviderClient(this).lastLocation.await()
        loc?.let { Triple(it.latitude, it.longitude, it.accuracy) }
    } catch (e: Exception) { null }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CHANNEL, "MV3D sync", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL).setContentTitle("MV3D").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download_done).setOngoing(true).build()
    }

    override fun onDestroy() { running = false; scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
