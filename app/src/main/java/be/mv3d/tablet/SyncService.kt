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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * De dienst die het werk doet.
 *
 * Om de vijf seconden vraagt ze aan mv3d.be of er iets klaarstaat, haalt het op, zet het in de map
 * die de machinist bij het koppelen gekozen heeft, en bevestigt. Meer niet.
 *
 * Ze draait als voorgronddienst met een melding. Dat is geen keuze maar een eis van Android: een
 * gewone dienst wordt na een paar minuten stilgelegd, en dan staat er 's morgens niets klaar.
 *
 * ── wat eruit is ──
 *
 * Hier stonden ook opdrachten op afstand (bestanden wissen, verplaatsen, terughalen), schermdeling
 * en het aanzetten van coördinatensystemen in Unicontrol. Dat spoor is er bewust uit: het maakte
 * van deze app een gereedschap dat van alles kón en waarvan de helft nooit gebruikt werd, terwijl
 * elk stuk ervan kon stukgaan op een werf waar niemand kan meekijken.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val prefs by lazy { Prefs(this) }

    companion object {
        const val CHANNEL = "mv3d_sync"
        const val INTERVAL_MS = 5_000L
        @Volatile var running = false; private set
        /** Wat er als laatste gebeurde. Het scherm leest dit; het is het enige wat het toont. */
        @Volatile var lastStatus: String = "—"
        @Volatile var machineName: String? = null
        /** Wanneer er voor het laatst met de server gepraat is (millis), of 0. */
        @Volatile var lastOk: Long = 0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("MV3D actief"))
        if (!running) { running = true; loop() }
        // START_STICKY: valt de dienst om — te weinig geheugen, een update van Android — dan start
        // het toestel haar zelf opnieuw. Zonder dit stopt de sync stil en merkt niemand het.
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
        val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeStr))
            ?: run { lastStatus = "map ongeldig"; return }

        val res = api.sync(mappenlijst(tree))
        res.name?.let { machineName = it }

        val gedaan = ArrayList<String>()
        for (f in res.files) {
            try { schrijf(tree, f.subfolder, f.name, api.download(f.url)); gedaan.add(f.id) }
            catch (e: Exception) { lastStatus = "${f.name}: ${e.message}"; return }
        }
        if (gedaan.isNotEmpty()) api.confirm(gedaan)

        lastOk = System.currentTimeMillis()
        lastStatus = if (gedaan.isEmpty()) "bij" else "${gedaan.size} bestand(en) binnengehaald"
    }

    /**
     * Wat er op de tablet staat, zodat het portaal het kan tonen.
     *
     * Maximaal vijf lagen diep en achthonderd bestanden: een Unicontrol-map van een jaar oud kan
     * duizenden bestanden dragen, en die lijst elke vijf seconden over een werf-4G duwen is
     * verspild. Lukt het niet, dan gaat de ronde zonder lijst door.
     */
    private fun mappenlijst(tree: DocumentFile): JSONObject? = try {
        val files = JSONArray()
        fun loop(dir: DocumentFile, prefix: String, diepte: Int) {
            if (diepte > 5 || files.length() >= 800) return
            for (f in dir.listFiles()) {
                val nm = f.name ?: continue
                val rel = if (prefix.isEmpty()) nm else "$prefix/$nm"
                if (f.isDirectory) loop(f, rel, diepte + 1)
                else files.put(JSONObject().put("path", rel).put("size", f.length()).put("m", f.lastModified()))
            }
        }
        loop(tree, "", 0)
        JSONObject().put("root", tree.name ?: "").put("files", files)
    } catch (_: Exception) { null }

    /**
     * Een bestand neerzetten, mappen aanmakend waar ze ontbreken.
     *
     * Bestaat het al, dan gaat het oude er eerst uit. Android maakt anders "Project (1).yml"
     * ernaast, en dan staat er in Unicontrol een werf die niemand bijwerkt.
     */
    private fun schrijf(root: DocumentFile, submap: String?, naam: String, bytes: ByteArray) {
        var dir = root
        submap?.split('/')?.filter { it.isNotBlank() }?.forEach { deel ->
            dir = dir.findFile(deel)?.takeIf { it.isDirectory } ?: dir.createDirectory(deel) ?: dir
        }
        dir.findFile(naam)?.delete()
        val doc = dir.createFile("application/octet-stream", naam) ?: throw RuntimeException("kon $naam niet aanmaken")
        contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) }
            ?: throw RuntimeException("kon $naam niet schrijven")
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "MV3D", NotificationManager.IMPORTANCE_MIN))
        }
        val open = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, open, flags)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("MV3D")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() { running = false; scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
