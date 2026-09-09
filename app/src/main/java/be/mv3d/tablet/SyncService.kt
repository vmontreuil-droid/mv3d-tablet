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
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream
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

    /** Hoe vaak een bestand al mislukt is, en wanneer we het opnieuw mogen proberen. */
    private val pogingen = HashMap<String, Int>()
    private val later = HashMap<String, Long>()

    companion object {
        const val CHANNEL = "mv3d_sync"
        /** Een tweede kanaal, want deze melding mág gezien worden. De sync-melding niet. */
        const val CHANNEL_UPDATE = "mv3d_update"
        const val INTERVAL_MS = 5_000L
        /** Eén keer per dag kijken of er een nieuwe versie staat. Vaker heeft geen doel. */
        const val UPDATE_MS = 24 * 60 * 60 * 1000L
        @Volatile var running = false; private set
        /** Wat er als laatste gebeurde. Het scherm leest dit; het is het enige wat het toont. */
        @Volatile var lastStatus: String = "—"
        @Volatile var machineName: String? = null
        /** Wanneer er voor het laatst met de server gepraat is (millis), of 0. */
        @Volatile var lastOk: Long = 0
        /**
         * Wat er misloopt, in gewone woorden — of niets.
         *
         * Dit staat los van lastOk, en dat is het hele punt. Contact met de server en het
         * wegschrijven van een bestand zijn twee verschillende dingen, en ze mogen niet hetzelfde
         * bolletje delen: dan leest een schrijffout op het scherm als "niet gekoppeld", terwijl
         * het portaal de kraan gewoon online ziet staan. Dan zoekt iedereen op de verkeerde plek.
         */
        @Volatile var lastFout: String? = null
        /** Welke build er klaarstaat om geïnstalleerd te worden, of 0. Het scherm leest dit. */
        @Volatile var updateKlaar: Int = 0
        @Volatile var updateNaam: String = ""
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
            try { tick() } catch (e: Exception) {
                lastStatus = "fout: ${e.message}"
                lastFout = "Geen verbinding met mv3d.be."
            }
            // Los van de bestanden, en het mag mislukken zonder gevolg: bijwerken hoort nooit in
            // de weg te staan van het werk.
            try { bijwerken() } catch (_: Exception) { }
            delay(INTERVAL_MS)
        }
    }

    /**
     * Eén keer per dag kijken of er een nieuwe versie is, en die stil klaarzetten.
     *
     * Wat hier níét gebeurt, is de installer openen. Dat deed de app vroeger, bij het opstarten,
     * en dat is een venster dat vanzelf voor je neus komt terwijl je aan het graven bent. Nu legt
     * ze een melding in de balk; de machinist tikt erop wanneer het hem past.
     *
     * De ene tik "Installeren" die daarna volgt, krijgen we er niet uit: Android laat een
     * zij-geladen app niet stil herinstalleren.
     */
    private suspend fun bijwerken() {
        // Staat er al iets klaar, dan is er niets te doen — behalve het onthouden na een herstart.
        val alKlaar = prefs.klaar()

        // Tenzij het intussen geïnstalleerd is. Dan hoort het klaargezette bestand weg.
        //
        // Zonder dit blijft de app voor eeuwig hangen op de versie die ze al draait: het merkje
        // "build 90 staat klaar" bleef staan nadat build 90 geïnstalleerd was, de apk bleef in de
        // cache liggen, en deze functie keerde elke ronde meteen terug — dus werd er nooit meer
        // gekeken of er een 91 was. Precies wat er gebeurde.
        if (alKlaar in 1..BuildConfig.VERSION_CODE) {
            prefs.wisKlaar()
            updateKlaar = 0
            Updater.ruimOp(this)
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(2)
            } catch (_: Exception) { }
            // En meteen opnieuw kijken in plaats van een dag te wachten: wie net bijgewerkt heeft,
            // hoort niet een dag achter te lopen op de versie die daarna kwam.
            prefs.setGekeken(0)
        } else if (alKlaar > 0) {
            if (Updater.staatKlaar(this, alKlaar)) {
                if (updateKlaar != alKlaar) {
                    updateKlaar = alKlaar; updateNaam = prefs.klaarNaam()
                    meldBijwerking()
                }
                return
            }
            // Het bestand is weg — de cache van een tablet wordt opgeruimd als de schijf vol loopt.
            prefs.wisKlaar(); updateKlaar = 0
        }

        val nu = System.currentTimeMillis()
        if (nu - prefs.gekeken() < UPDATE_MS) return
        prefs.setGekeken(nu)

        val u = Updater.check() ?: return
        if (!Updater.haal(this, u)) return
        prefs.setKlaar(u.versionCode, u.versionName)
        updateKlaar = u.versionCode; updateNaam = u.versionName
        meldBijwerking()
    }

    /** De melding in de balk. Erop tikken opent de installer van Android. */
    private fun meldBijwerking() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_UPDATE, "MV3D bijwerken", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 2, Updater.installatie(this, updateKlaar), flags)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_UPDATE) else @Suppress("DEPRECATION") Notification.Builder(this)
        nm.notify(2, b.setContentTitle("Nieuwe versie klaar")
            .setContentText("Tik om MV3D bij te werken.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    private suspend fun tick() {
        val code = prefs.code(); val server = prefs.server(); val treeStr = prefs.tree()
        if (code.isBlank() || treeStr.isBlank()) { lastStatus = "niet gekoppeld"; return }
        val api = Api(server, code)
        val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeStr))
            ?: run { lastStatus = "map ongeldig"; lastFout = "De map is niet meer bereikbaar. Wijs ze opnieuw aan."; return }

        val res = api.sync(mappenlijst(tree))

        // Er is contact geweest. Dat tekenen we hier, en niet onderaan.
        //
        // Stond het onderaan, dan wiste één bestand dat niet wilde wegschrijven het hele bolletje
        // uit — en dan zei de app "niet gekoppeld" terwijl ze net nog met de server gepraat had.
        lastOk = System.currentTimeMillis()
        res.name?.let { machineName = it }

        // ── de bestanden, en eentje dat faalt houdt de rest niet tegen ──
        //
        // Hier stond `return` in de vangst. Eén bestand dat niet wegkwam, en de ronde stopte: van
        // een Unicontrol-werf van vijf stukken kwam alleen het eerste aan, en niemand kon zien dat
        // er vier ontbraken. Nu gaat ze door en onthoudt ze wat er misging.
        val gedaan = ArrayList<String>()
        val mislukt = ArrayList<String>()
        var uitgesteld = 0
        val nu = System.currentTimeMillis()

        for (f in res.files) {
            // Een bestand dat blijft weigeren, niet elke vijf seconden opnieuw over een werf-4G
            // slepen. Na elke misser duurt het langer voor we het opnieuw proberen — tot tien
            // minuten. Opgeven doen we niet: wat het ook was, het kan morgen over zijn.
            val wacht = later[f.id]
            if (wacht != null && wacht > nu) { uitgesteld++; continue }

            try {
                schrijf(tree, f.subfolder, f.name) { uit -> api.download(f.url, uit) }
                gedaan.add(f.id)
                later.remove(f.id); pogingen.remove(f.id)
            } catch (e: Exception) {
                val n = (pogingen[f.id] ?: 0) + 1
                pogingen[f.id] = n
                later[f.id] = nu + minOf(30_000L * (1L shl minOf(n - 1, 5)), 600_000L)
                mislukt.add(f.name)
                lastStatus = "${f.name}: ${e.message}"
            }
        }

        // ── en wat er van hier wég moet ──
        //
        // Het portaal kan vragen om een bestand terug te sturen: een aangepast ontwerp, een
        // as-built. Alleen wat in de gekozen map staat, en alleen wat gevraagd is. Twee per ronde:
        // een tablet in een cabine hangt aan een werf-4G, en dit mag het binnenhalen van een
        // nieuwe werf niet in de weg zitten.
        val opgestuurd = ArrayList<PullResult>()
        for (q in res.pull.take(2)) {
            try {
                val doc = zoekBestand(tree, q.path) ?: throw RuntimeException("staat er niet meer")
                val lengte = doc.length()
                api.upload(q.url, q.token, lengte) {
                    contentResolver.openInputStream(doc.uri)
                        ?: throw RuntimeException("kon niet gelezen worden")
                }
                opgestuurd.add(PullResult(q.id, true, lengte, null))
            } catch (e: Exception) {
                opgestuurd.add(PullResult(q.id, false, 0L, e.message ?: "onbekend"))
            }
        }

        // Bevestigen wat gelukt is, ook als er iets misging. Anders blijft een werf die op één
        // bestand na binnen is, in zijn geheel in de wachtrij staan.
        if (gedaan.isNotEmpty() || opgestuurd.isNotEmpty()) api.confirm(gedaan, opgestuurd)

        lastFout = if (mislukt.isEmpty()) null
            else if (mislukt.size == 1) "${mislukt[0]} kon niet weggeschreven worden."
            else "${mislukt.size} bestanden konden niet weggeschreven worden."
        val weg = opgestuurd.count { it.ok }
        lastStatus = when {
            mislukt.isNotEmpty() -> lastStatus
            gedaan.isNotEmpty() -> "${gedaan.size} bestand(en) binnengehaald"
            weg > 0 -> "$weg bestand(en) opgestuurd"
            uitgesteld > 0 -> "$uitgesteld wacht(en) op een nieuwe poging"
            else -> "bij"
        }
    }

    /**
     * Eén bestand terugvinden aan het pad dat wij zelf gemeld hebben.
     *
     * Dat pad komt uit onze eigen mappenlijst, dus het hoort te bestaan — maar tussen die lijst en
     * deze vraag kan er een dag zitten. Vindt hij het niet, dan zeggen we dat, en dan staat er in
     * het portaal "staat er niet meer" in plaats van een opdracht die eeuwig op "bezig" blijft.
     */
    private fun zoekBestand(tree: DocumentFile, pad: String): DocumentFile? {
        val delen = pad.replace('\\', '/').split('/').filter { it.isNotBlank() && it != ".." }
        if (delen.isEmpty()) return null
        var hier: DocumentFile = tree
        for ((i, deel) in delen.withIndex()) {
            val volgende = hier.findFile(deel) ?: return null
            if (i == delen.lastIndex) return if (volgende.isFile) volgende else null
            if (!volgende.isDirectory) return null
            hier = volgende
        }
        return null
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
    private fun schrijf(root: DocumentFile, submap: String?, naam: String, vul: (OutputStream) -> Unit) {
        var dir = root
        submap?.split('/')?.filter { it.isNotBlank() }?.forEach { deel ->
            dir = dir.findFile(deel)?.takeIf { it.isDirectory } ?: dir.createDirectory(deel) ?: dir
        }
        dir.findFile(naam)?.delete()

        // Het mime-type uit de extensie halen, en niet altijd octet-stream opgeven.
        //
        // Android bepaalt de naam op schijf mede uit het mime-type: past de extensie er niet bij,
        // dan plakt het de zijne erachter. "werf.xml" met octet-stream wordt zo "werf.xml.bin".
        // Voor ons ziet dat eruit als gelukt — het bestand staat er, de wachtrij is leeg — maar
        // Unicontrol zoekt naar werf.xml en vindt niets. Dan is de werf overgekomen en toch niet
        // te zien, en er staat nergens een fout.
        val doc = dir.createFile(mimeVan(naam), naam) ?: throw RuntimeException("kon $naam niet aanmaken")

        // En als het toch gebeurde, zetten we de naam terug.
        if (doc.name != naam) {
            try { DocumentsContract.renameDocument(contentResolver, doc.uri, naam) } catch (_: Exception) { }
        }

        contentResolver.openOutputStream(doc.uri)?.use { vul(it) }
            ?: throw RuntimeException("kon $naam niet schrijven")
    }

    /** Het mime-type dat bij deze extensie hoort, of octet-stream als Android het niet kent. */
    private fun mimeVan(naam: String): String {
        val ext = naam.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
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
