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
/** Het werkblad dat elke McNav-werf nodig heeft. Zie werkbladBij. */
private const val WERKBLAD = "workspace.mgdb"

class SyncService : Service() {
    // De meldingen en de foutregel in de taal die in de app gekozen is (zie Taal.kt).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Taal.omhul(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val prefs by lazy { Prefs(this) }

    /** Hoe vaak een bestand al mislukt is, en wanneer we het opnieuw mogen proberen. */
    private val pogingen = HashMap<String, Int>()
    private var vastGeprobeerd = 0L
    private val later = HashMap<String, Long>()

    /** Waar een werf ligt: oosting, noording en de naam van het stelsel zoals de tablet die meldt. */
    private data class Plek(val x: Double, val z: Double, val cs: String)

    /** Wat we al uit een Project.yml gelezen hebben: werf → (datum van dat bestand, plek). */
    private val plekGeheugen = HashMap<String, Pair<Long, Plek?>>()

    /** Of deze dienst sinds haar start al gekeken heeft naar een nieuwe versie (zie bijwerken). */
    private var gekekenSindsStart = false

    companion object {
        const val CHANNEL = "mv3d_sync"
        /** Een tweede kanaal, want deze melding mág gezien worden. De sync-melding niet. */
        const val CHANNEL_UPDATE = "mv3d_update"
        const val INTERVAL_MS = 5_000L
        /** Elk uur kijken of er een nieuwe versie staat, en bij elke start. Eén keer per dag was te traag. */
        const val UPDATE_MS = 60 * 60 * 1000L
        @Volatile var running = false; private set
        /**
         * Wat er als laatste gebeurde.
         *
         * Hier stond "het scherm leest dit; het is het enige wat het toont". Dat klopt niet meer:
         * MainActivity leest alleen `lastFout`. Dit veld wordt geschreven en nergens gelezen —
         * het gaat ook niet naar de server. Het staat er nog omdat het bij het opsporen van een
         * storing het eerste is wat je wil weten, en omdat het scherm het zou kunnen tonen zodra
         * daar plaats voor is. De teksten zijn alvast vertaald.
         */
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
        /**
         * De werven die op dit toestel staan, zoals ze in de mappenlijst zitten.
         *
         * Voor het scherm "werf doorsturen": de machinist kiest er een en tikt de code van de andere
         * machine in. Hier bijgehouden omdat de lijst er bij elke ronde toch al is; hem in het scherm
         * opnieuw opbouwen zou de map een tweede keer doorlopen.
         */
        @Volatile var werven: List<String> = emptyList()
        @Volatile var updateKlaar: Int = 0
        @Volatile var updateNaam: String = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(getString(R.string.mv3d_actief)))
        // Zijn wij eigenaar van dit toestel, dan geven we onszelf de toestemming voor de plek. In een
        // cabine staat niemand klaar om een venster weg te tikken; zonder eigenaarschap gebeurt er
        // niets en stuurt de app gewoon geen plek mee.
        Beheerder.plekToestaan(this)
        if (!running) { running = true; loop() }
        // START_STICKY: valt de dienst om — te weinig geheugen, een update van Android — dan start
        // het toestel haar zelf opnieuw. Zonder dit stopt de sync stil en merkt niemand het.
        return START_STICKY
    }

    private fun loop() = scope.launch {
        while (isActive) {
            try { tick() } catch (e: Exception) {
                lastStatus = "fout: ${e.message}"
                // Niet elke fout is "geen bereik".
                //
                // Hier stond onvoorwaardelijk fout_geen_verbinding. Maar tick() werpt ook bij een
                // HTTP 401 of 404 (een ingetrokken code, een machine die gewist is), bij kapotte
                // JSON, bij een volle schijf en bij een SecurityException op de map. De machinist
                // las dan "Geen verbinding met mv3d.be" en ging de antenne zoeken terwijl zijn code
                // ingetrokken was. Dat ondergraaft precies de scheiding die deze dienst maakt
                // tussen "wij kunnen er niet bij" en "er is iets anders aan de hand".
                lastFout = foutTekst(e)
            }
            // Los van de bestanden, en het mag mislukken zonder gevolg: bijwerken hoort nooit in
            // de weg te staan van het werk.
            try { bijwerken() } catch (_: Exception) { }
            delay(INTERVAL_MS)
        }
    }

    /**
     * Welke zin hoort er bij deze fout?
     *
     * Drie soorten, en ze sturen de machinist elk een andere kant op:
     *
     *   · het netwerk — dan klopt "geen verbinding" en heeft wachten zin;
     *   · de server antwoordt wél maar met een foutcode (401, 404, 500) — dan is er iets met de
     *     koppeling of met ons, en helpt wachten op bereik niets;
     *   · al de rest — een volle schijf, een map waar we niet in mogen, kapotte JSON.
     *
     * De melding draagt in de laatste twee gevallen de eigenlijke reden mee. Die is niet altijd
     * mooi, maar hij is waar, en hij is het enige waarmee jij aan de telefoon iets kunt.
     */
    private fun foutTekst(e: Exception): String {
        val m = e.message ?: ""
        return when {
            e is java.io.IOException && !m.startsWith("sync ") -> getString(R.string.fout_geen_verbinding)
            m.startsWith("sync ") -> getString(R.string.fout_server, m.take(60))
            e is SecurityException -> getString(R.string.fout_map_geen_toegang)
            else -> getString(R.string.fout_onbekend, m.take(80).ifEmpty { e.javaClass.simpleName })
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

        // ── bij de start altijd, daarna elk uur ──
        //
        // Hier stond één keer per dag, onthouden over een herstart heen. Gevolg op 11/9/2026: build
        // 98 stond online, maar Picon had die ochtend al gekeken en bleef op 97 — ook na het
        // herstarten van de app, want het tijdstip stond in de voorkeuren. Wie iets bijwerkt en het
        // programma herstart, verwacht dat het dan kijkt. Eén blik per uur naar GitHub is niets.
        val nu = System.currentTimeMillis()
        if (!gekekenSindsStart) {
            gekekenSindsStart = true
        } else if (nu - prefs.gekeken() < UPDATE_MS) return
        prefs.setGekeken(nu)

        val u = Updater.check() ?: return
        if (!Updater.haal(this, u)) return
        prefs.setKlaar(u.versionCode, u.versionName)
        updateKlaar = u.versionCode; updateNaam = u.versionName
        // ── als wij eigenaar zijn: meteen, zonder iemand ──
        //
        // Gevraagd: het bijwerken moet volledig vanzelf gaan. Op een tablet die als eigenaar gezet
        // is, kan dat — zie Beheerder.kt. Dan hoeft er geen melding te komen waar in een cabine
        // toch niemand op tikt, en loopt de vloot niet maanden achter.
        //
        // Lukt het niet, dan blijft alles zoals het was: de melding in de balk en één tik. Daarom
        // wordt er pas gemeld als het stille pad niet gewerkt heeft, en niet andersom — anders
        // staat er een melding voor iets dat al gebeurd is.
        if (Updater.installeerStil(this, u.versionCode)) return
        meldBijwerking()
    }

    /** De melding in de balk. Erop tikken opent de installer van Android. */
    private fun meldBijwerking() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_UPDATE, getString(R.string.kanaal_bijwerken), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 2, Updater.installatie(this, updateKlaar), flags)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_UPDATE) else @Suppress("DEPRECATION") Notification.Builder(this)
        nm.notify(2, b.setContentTitle(getString(R.string.nieuwe_versie))
            .setContentText(getString(R.string.tik_om_bij_te_werken))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    private suspend fun tick() {
        val code = prefs.code(); val server = prefs.server(); val treeStr = prefs.tree()
        if (code.isBlank() || treeStr.isBlank()) { lastStatus = getString(R.string.st_niet_gekoppeld); return }
        val api = Api(server, code)
        val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeStr))
            ?: run { lastStatus = getString(R.string.st_map_ongeldig); lastFout = getString(R.string.fout_map_niet_bereikbaar); return }

        // ── kan hij er werkelijk in kijken? ──
        //
        // fromTreeUri geeft altijd iets terug, ook als de toestemming weg is. Android geeft die
        // toestemming per installatie: na het bijwerken van de app blijft het onthouden adres staan
        // maar is de sleutel weg. Dan ziet de app een map die bestaat en leeg is.
        //
        // Gemeten, en het was duur. Na build 96 meldde deze tablet nul bestanden waar er honderd-
        // vierentwintig stonden, en die lege lijst overschreef op de server de goede: in het
        // portaal verdwenen achtentwintig werven alsof ze gewist waren. Ondertussen zei het scherm
        // "gekoppeld" en klopte de app rustig elke vijf seconden aan.
        //
        // Dus: geen naam of niet leesbaar is geen lege map maar een gesloten deur. We melden ons
        // wel — anders lijkt het toestel offline en zoek je het in de verkeerde hoek — maar we
        // sturen géén lijst mee. Wat de server weet blijft dan staan tot de map weer open is.
        val leesbaar = tree.name != null && tree.canRead()
        if (!leesbaar) {
            lastStatus = getString(R.string.st_map_niet_bereikbaar)
            lastFout = getString(R.string.fout_map_geen_toegang)
        }

        val res = api.sync(if (leesbaar) mappenlijst(tree) else null, Toestelplek.huidige(this))

        // Er is contact geweest. Dat tekenen we hier, en niet onderaan.
        //
        // Stond het onderaan, dan wiste één bestand dat niet wilde wegschrijven het hele bolletje
        // uit — en dan zei de app "niet gekoppeld" terwijl ze net nog met de server gepraat had.
        lastOk = System.currentTimeMillis()
        res.name?.let { machineName = it }

        // ── één keer: van het willekeurige id naar het vaste ──
        //
        // Zodat ook een tablet die al gekoppeld was, een herinstallatie overleeft (zie Prefs.installatie).
        // Lukt het niet — geen bereik, een oude server — dan de volgende ronde nog eens, hoogstens elk uur.
        if (!prefs.vastGemaakt() && System.currentTimeMillis() - vastGeprobeerd > 3_600_000L) {
            vastGeprobeerd = System.currentTimeMillis()
            val vast = prefs.vastId()
            val nu = prefs.installatie()
            if (vast != null) {
                if (nu == vast || Api.overschakelen(server, vast, nu, code)) prefs.zetVastGemaakt(vast)
            }
        }

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

        // ── het werkblad dat CHCnav niet zelf meestuurt ──
        //
        // Een McNav-werf draagt een workspace.mgdb: zijn entiteitendatabank, een DWG. Zonder dat
        // bestand opent de werf wél en crasht het programma bij het opbouwen van het
        // machinescherm:
        //
        //     NullPointerException: getObjectId(...) must not be null
        //         at GuideLineManager.addSectionPlane
        //
        // Wij schrijven geen DWG, dus de server stuurt het niet mee. Het staat wél op elk
        // CHC-toestel, in het lege fabrieksproject Default2DProject — dus halen we het daar op.
        // Zo hoeft er niets van CHCNav verspreid te worden en werkt het op elk model.
        if (gedaan.isNotEmpty()) werkbladBij(tree, res.guidance, res.files)

        // ── een Nuwa-werf moet ook in Nuwa's projectenlijst ──
        //
        // Nuwa toont geen projectmap die niet in zijn eigen databank staat (Projects/project, tabel
        // TbProject). De server stuurt de rij mee als mv3d-project.json; die zetten we erin.
        for (f in res.files) {
            if (f.name != "mv3d-project.json" || f.id !in gedaan) continue
            try { nuwaProject(tree, f.subfolder) } catch (e: Exception) { lastStatus = "Nuwa-project: ${e.message}" }
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

        // ── en wat er van de tablet af moet ──
        //
        // Wissen stond hier ooit en is er bewust uitgehaald, samen met de rest van de opdrachten op
        // afstand. Het komt terug om één reden: in de Convertor staat een knop "wissen" bij elke werf
        // op een machine, en bij deze tablet deed die niets. De opdracht kwam aan en bleef hangen.
        //
        // Dezelfde grendels als op de veldcomputer:
        //   · alleen paden die deze tablet zelf gemeld heeft — dat zeeft de server al
        //   · alleen bestanden binnen de gekozen map; zoekBestand klimt nooit hoger en slaat ".." over
        //   · een bestand dat er al niet meer is, telt als gelukt, anders blijft die opdracht eeuwig
        //     in de rij staan omdat wij niet kunnen doen wat al gebeurd is
        //
        // Eén ding meer dan op de veldcomputer: blijft er van de werf een lege map over, dan gaat die
        // ook weg. Unicontrol toont elke map als een project, en een leeg project dat blijft staan
        // leest op de tablet als een werf die niet gewist is. Alleen mappen die wérkelijk leeg zijn,
        // en nooit de gekozen map zelf.
        val gewist = ArrayList<PullResult>()
        val geraakt = LinkedHashSet<List<String>>()
        for (q in res.remove.take(20)) {
            try {
                val delen = q.path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != ".." }
                val doc = zoekBestand(tree, q.path)
                if (doc == null) { gewist.add(PullResult(q.id, true, 0L, null)); continue }
                val lengte = doc.length()
                if (!doc.delete()) throw RuntimeException("kon niet gewist worden")
                gewist.add(PullResult(q.id, true, lengte, null))
                if (delen.size > 1) geraakt.add(delen.dropLast(1))
            } catch (e: Exception) {
                gewist.add(PullResult(q.id, false, 0L, e.message ?: "onbekend"))
            }
        }
        fun zoekMap(delen: List<String>): DocumentFile? {
            var hier: DocumentFile = tree
            for (deel in delen) hier = hier.findFile(deel)?.takeIf { it.isDirectory } ?: return null
            return hier
        }
        for (m in geraakt.sortedByDescending { it.size }) {
            var delen = m
            while (delen.isNotEmpty()) {
                val map = zoekMap(delen) ?: break
                if (map.listFiles().isNotEmpty()) break
                if (!map.delete()) break
                delen = delen.dropLast(1)
            }
        }

        // ── en werfmappen die een andere naam krijgen ──
        //
        // Gevraagd vanuit de Convertor: de naam van een werf wijzigen, ook hier op de tablet. Bij
        // Unicontrol is de naam van de map de naam van de werf, en het Project.yml verwijst alleen
        // naar bestanden ín die map — dus de map hernoemen volstaat, en de rest blijft kloppen.
        //
        // Alleen de map zelf, alleen als ze er nog is, en nooit over een map heen die al zo heet:
        // twee werven samenvoegen is geen hernoemen.
        val hernoemd = ArrayList<PullResult>()
        for (q in res.hernoem.take(10)) {
            try {
                val delen = q.path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != ".." }
                if (delen.isEmpty()) throw RuntimeException("geen werfmap opgegeven")
                val naar = q.naar.trim()
                if (naar.isEmpty() || naar == "." || naar == ".." || naar.any { it in "/\\:*?\"<>|" }) {
                    throw RuntimeException("die naam kan geen map zijn")
                }
                val map = zoekMap(delen) ?: throw RuntimeException("de werf staat er niet meer")
                val ouder = if (delen.size > 1) zoekMap(delen.dropLast(1)) else tree
                if (ouder == null) throw RuntimeException("de bovenliggende map staat er niet meer")
                if (ouder.listFiles().any { (it.name ?: "").equals(naar, ignoreCase = true) }) {
                    throw RuntimeException("er staat al een werf met die naam")
                }
                val oud = map.name ?: delen.last()
                // Een CHC-werf herken je aan haar .hcprj — op de naam van dat bestand en niet op die
                // van de map, want een werf die ooit half hernoemd is draagt er twee verschillende.
                val hcprj = map.listFiles().firstOrNull { it.isFile && (it.name ?: "").endsWith(".hcprj", ignoreCase = true) }
                if (hcprj != null) chcHernoem(ouder, map, oud, (hcprj.name ?: "").removeSuffix(".hcprj").removeSuffix(".HCPRJ"), naar)
                else if (hernoemIn(ouder, map, oud, naar) == null) throw RuntimeException("de map kon niet hernoemd worden")
                hernoemd.add(PullResult(q.id, true, 0L, null))
            } catch (e: Exception) {
                hernoemd.add(PullResult(q.id, false, 0L, e.message ?: "onbekend"))
            }
        }

        // Bevestigen wat gelukt is, ook als er iets misging. Anders blijft een werf die op één
        // bestand na binnen is, in zijn geheel in de wachtrij staan.
        if (gedaan.isNotEmpty() || opgestuurd.isNotEmpty() || gewist.isNotEmpty() || hernoemd.isNotEmpty()) {
            api.confirm(gedaan, opgestuurd, gewist, hernoemd)
        }

        // De waarschuwing over de map mag hier niet uitgewist worden.
        //
        // Hier stond `lastFout = if (mislukt.isEmpty()) null else …`, en dat wist alles — ook de
        // melding die hierboven gezet werd toen bleek dat de app niet meer in de werfmap mag. De
        // wachtrij is normaal leeg, dus dat gebeurde meteen in dezelfde ronde. Samen met `lastOk`
        // een paar regels hoger betekende dat: een tablet die na een bijwerking zijn maptoestemming
        // kwijt is, toont een volmaakt gezond scherm. Groen bolletje, "Gekoppeld", geen fout.
        //
        // Dat is precies de storing die hierboven beschreven staat en die achtentwintig werven uit
        // het portaal liet verdwijnen. Ze stond er nog.
        lastFout = when {
            !leesbaar -> getString(R.string.fout_map_geen_toegang)
            mislukt.isEmpty() -> null
            mislukt.size == 1 -> getString(R.string.fout_een_niet_weggeschreven, mislukt[0])
            else -> getString(R.string.fout_niet_weggeschreven, mislukt.size)
        }
        val weg = opgestuurd.count { it.ok }
        val af = gewist.count { it.ok }
        val anders = hernoemd.count { it.ok }
        lastStatus = when {
            // Hetzelfde voor de statusregel: een gesloten map is geen "bij".
            !leesbaar -> getString(R.string.st_map_niet_bereikbaar)
            mislukt.isNotEmpty() -> lastStatus
            gedaan.isNotEmpty() -> getString(R.string.st_binnengehaald, gedaan.size)
            weg > 0 -> getString(R.string.st_opgestuurd, weg)
            af > 0 -> getString(R.string.st_gewist, af)
            anders > 0 -> getString(R.string.st_hernoemd, anders)
            uitgesteld > 0 -> getString(R.string.st_uitgesteld, uitgesteld)
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
    /**
     * Het werkblad van CHCnav bijzetten in elke werf die net binnengekomen is.
     *
     * Alleen voor CHCnav, en alleen als het er nog niet staat. Het bronbestand zoeken we op twee
     * plekken, omdat het ervan afhangt welke map de machinist aangewezen heeft: staat hij in
     * Projects, dan is Default2DProject een zus van de werf; staat hij een laag hoger, dan zit er
     * nog Projects/ tussen.
     *
     * Vinden we het niet, dan zeggen we dat. Stil overslaan zou betekenen dat de werf netjes
     * aankomt en het programma crasht zodra de machinist hem opent — en dan zoekt hij bij ons de
     * fout niet, want er stond niets.
     */
    private fun werkbladBij(tree: DocumentFile, besturing: String?, files: List<RemoteFile>) {
        if (!"CHCNAV".equals(besturing, ignoreCase = true)) return

        // De werfmappen die in deze ronde iets gekregen hebben: het eerste deel van elke submap.
        val werven = files.mapNotNull { f ->
            f.subfolder?.replace('\\', '/')?.split('/')?.firstOrNull { it.isNotBlank() && it != ".." }
        }.toSet()
        if (werven.isEmpty()) return

        val bron = zoekBestand(tree, "Default2DProject/$WERKBLAD")
            ?: zoekBestand(tree, "Projects/Default2DProject/$WERKBLAD")
        if (bron == null) {
            lastStatus = "$WERKBLAD niet gevonden in Default2DProject — McNav kan deze werf niet openen"
            return
        }

        for (werf in werven) {
            // Staat het er al, dan blijft het staan: dit is een bestand van het toestel zelf.
            if (zoekBestand(tree, "$werf/$WERKBLAD") != null) continue
            try {
                schrijf(tree, werf, WERKBLAD) { uit ->
                    contentResolver.openInputStream(bron.uri)?.use { it.copyTo(uit, 64 * 1024) }
                        ?: throw RuntimeException("kon $WERKBLAD niet lezen")
                }
            } catch (e: Exception) {
                lastStatus = "$WERKBLAD naar $werf: ${e.message}"
            }
        }
    }

    /**
     * Een CHCnav-werf een andere naam geven.
     *
     * Bij Unicontrol volstaat de map. Bij McNav niet, en dat is gemeten in de emulator (15/9/2026):
     * een kopie met alleen een andere mapnaam verdwijnt zonder melding uit de projectlijst, want McNav
     * zoekt `<map>/<map>.json`. De naam staat op vier plaatsen:
     *
     *   de map zelf
     *   `<naam>.hcprj` en `<naam>.json`         de bestandsnamen
     *   in dat .json: `name` en `crsPath`       crsPath is een volledig pad met de map erin
     *   in survey-stakeout.db                   het lijnwerk (default_basemap_record.PATH) staat
     *                                           er als volledig pad
     *
     * Met die vier aangepast opende de werf in McNav, met oppervlak en lijnwerk.
     *
     * Alleen `/Projects/<naam>/` wordt vervangen, en niet elke `/<naam>/`: een ontwerp heet vaak net
     * als de werf ("DesignData/MV3D KANT EN KLAAR/") en die map blijft zoals ze is.
     *
     * Eerst alles voorbereiden, dan pas iets veranderen: lukt het lezen niet, dan is er niets aangeraakt.
     *
     * `oudMap` is de naam van de map, `oud` die van het .hcprj. Normaal zijn ze gelijk. Zijn ze het
     * niet — een werf die eerder half hernoemd is — dan maakt dit ze weer gelijk.
     */
    private fun chcHernoem(ouder: DocumentFile, map: DocumentFile, oudMap: String, oud: String, naar: String) {
        // Een databank waar McNav middenin schrijft, draagt een -journal of -wal. Wie die overschrijft,
        // gooit weg wat McNav nog niet weggeschreven heeft.
        if (map.listFiles().any { val n = it.name ?: ""; n.startsWith("survey-stakeout.db-") }) {
            throw RuntimeException("de werf is open in McNav — open een andere werf en vraag het opnieuw")
        }
        val namen = listOf(oud, oudMap).distinct()

        val json = map.findFile("$oud.json")?.takeIf { it.isFile }
        val nieuweJson = json?.let { doc ->
            val tekst = contentResolver.openInputStream(doc.uri)?.use { it.readBytes().decodeToString() }
                ?: throw RuntimeException("$oud.json kon niet gelezen worden")
            chcJson(tekst, namen, naar)
        }
        val db = map.findFile("survey-stakeout.db")?.takeIf { it.isFile }
        val nieuweDb = db?.let { chcDb(it, namen, naar) }

        try {
            val werf = hernoemIn(ouder, map, oudMap, naar) ?: throw RuntimeException("de map kon niet hernoemd worden")

            // Vanaf hier is de map hernoemd. Wat nog misloopt, wordt genoemd — niet teruggedraaid,
            // want een half teruggedraaide werf is nog verder van huis. Een volgende hernoeming
            // herstelt het wel: die leest de oude naam uit het .hcprj en niet uit de map.
            val mis = ArrayList<String>()
            if (oud != naar) {
                val h = werf.findFile("$oud.hcprj")
                if (h == null || hernoemIn(werf, h, "$oud.hcprj", "$naar.hcprj") == null) mis.add("$oud.hcprj")
            }
            if (json != null && nieuweJson != null) {
                try {
                    val doc = werf.findFile("$oud.json") ?: throw RuntimeException()
                    contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(nieuweJson.toByteArray()) }
                        ?: throw RuntimeException()
                    if (oud != naar && hernoemIn(werf, doc, "$oud.json", "$naar.json") == null) throw RuntimeException()
                } catch (_: Exception) { mis.add("$oud.json") }
            }
            if (nieuweDb != null) {
                try {
                    val doc = werf.findFile("survey-stakeout.db") ?: throw RuntimeException()
                    contentResolver.openOutputStream(doc.uri, "wt")?.use { uit -> nieuweDb.inputStream().use { it.copyTo(uit) } }
                        ?: throw RuntimeException()
                } catch (_: Exception) { mis.add("survey-stakeout.db") }
            }
            if (mis.isNotEmpty()) throw RuntimeException("map hernoemd, maar niet bijgewerkt: " + mis.joinToString(", "))
        } finally {
            nieuweDb?.delete()
        }
    }

    /**
     * Hernoemen, en dan kijken wat er werkelijk staat.
     *
     * Gemeten op Android 9 (BlueStacks, 15/9/2026): DocumentsContract.renameDocument hernoemt de map
     * wél, en gooit daarna "Missing file" op het oude pad — dus renameTo zegt false. Wie dat gelooft,
     * stopt halverwege en laat een werf achter waarvan de map een nieuwe naam heeft en de bestanden
     * erin de oude: in McNav verdwijnt ze. Dus: staat de nieuwe naam er en de oude niet meer, dan is
     * het gelukt, wat de oproep ook zei.
     */
    private fun hernoemIn(ouder: DocumentFile, doc: DocumentFile, oud: String, naar: String): DocumentFile? {
        if (doc.renameTo(naar)) return doc
        val nieuw = ouder.findFile(naar) ?: return null
        return if (ouder.findFile(oud) == null) nieuw else null
    }

    /** Het projectbestand met de nieuwe naam: `name`, en de map in `crsPath`. */
    private fun chcJson(tekst: String, oud: List<String>, naar: String): String {
        // Op de tekst en niet via JSONObject: dat zet de sleutels in een andere volgorde en schrijft
        // elke / als \/. Het werkt allebei, maar een bestand dat er anders uitziet dan het hunne is een
        // bestand waar je bij een storing aan gaat twijfelen. Een werfnaam draagt geen " of \ (de
        // server laat die niet toe).
        var uit = tekst.replace(Regex("(\"name\"\\s*:\\s*\")[^\"]*(\")"), "$1" + Regex.escapeReplacement(naar) + "$2")
        for (o in oud) {
            uit = uit.replace("/Projects/$o/", "/Projects/$naar/").replace("\\/Projects\\/$o\\/", "\\/Projects\\/$naar\\/")
        }
        // En nagaan dat het gelukt is. Staat de naam er anders in dan we denken, dan liever stoppen
        // dan een .json wegschrijven waarin de oude naam blijft staan.
        if (JSONObject(uit).optString("name") != naar) throw RuntimeException("de naam in het .json staat er anders in dan verwacht")
        return uit
    }

    /**
     * Een kopie van survey-stakeout.db met de volledige paden naar de nieuwe map.
     *
     * Elke tekstkolom van elke gewone tabel, niet alleen die ene waar we het gezien hebben: een werf
     * die McNav zelf aanmaakte kan ook elders een volledig pad dragen. De r-tree is een virtuele tabel
     * en draagt geen tekst; die blijft buiten schot.
     */
    private fun chcDb(doc: DocumentFile, oud: List<String>, naar: String): java.io.File {
        val kopie = java.io.File.createTempFile("werf", ".db", cacheDir)
        try {
            contentResolver.openInputStream(doc.uri)?.use { inn -> kopie.outputStream().use { inn.copyTo(it) } }
                ?: throw RuntimeException("survey-stakeout.db kon niet gelezen worden")
            val tot = "/Projects/$naar/"
            val sql = android.database.sqlite.SQLiteDatabase.openDatabase(
                kopie.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            try {
                // Android zet een databank bij het openen soms stil in WAL-modus, en dat staat dan in
                // de kop van het bestand. McNav schrijft de gewone modus (bytes 18–19 op 1, gemeten op
                // drie werven); zo moet ze terugkomen.
                sql.disableWriteAheadLogging()
                val tabellen = ArrayList<String>()
                sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND sql NOT LIKE 'CREATE VIRTUAL%'", null).use { c ->
                    while (c.moveToNext()) tabellen.add(c.getString(0))
                }
                for (t in tabellen) {
                    val kolommen = ArrayList<String>()
                    sql.rawQuery("PRAGMA table_info(\"${t.replace("\"", "\"\"")}\")", null).use { c ->
                        val naam = c.getColumnIndex("name"); val soort = c.getColumnIndex("type")
                        while (c.moveToNext()) if ((c.getString(soort) ?: "").uppercase().contains("TEXT")) kolommen.add(c.getString(naam))
                    }
                    for (k in kolommen) {
                        val tq = "\"" + t.replace("\"", "\"\"") + "\""
                        val kq = "\"" + k.replace("\"", "\"\"") + "\""
                        for (o in oud) {
                            val van = "/Projects/$o/"
                            sql.execSQL("UPDATE $tq SET $kq = replace($kq, ?, ?) WHERE instr($kq, ?) > 0", arrayOf(van, tot, van))
                        }
                    }
                }
            } finally { sql.close() }
            return kopie
        } catch (e: Exception) {
            kopie.delete()
            throw e
        }
    }

    /**
     * De projectrij van een Nuwa-werf in Nuwa's eigen databank zetten.
     *
     * `submap` is TersusSurvey/Projects/<werf> (of Projects/<werf> als TersusSurvey zelf aangewezen is);
     * de databank `project` staat één laag hoger. Nagemeten op Nuwa 2.5 (BlueStacks, 15/9/2026): met
     * deze rij verschijnt de werf in de lijst en opent ze, met stelsel en uitzetpunten.
     *
     * Een kopie bewerken en terugzetten, want SQLite kan niet door een DocumentFile heen. Nuwa bewaart
     * die databank in WAL-modus: staat er nog een niet-lege -wal naast, dan hoort die bij de kopie, anders
     * gaat wat Nuwa net schreef verloren. Terug gaat alles in het hoofdbestand, en de -wal en -shm weg.
     */
    private fun nuwaProject(tree: DocumentFile, submap: String?) {
        val delen = submap?.replace('\\', '/')?.split('/')?.filter { it.isNotBlank() } ?: return
        if (delen.size < 2 || !delen[delen.size - 2].equals("Projects", ignoreCase = true)) return
        var projects: DocumentFile = tree
        for (deel in delen.dropLast(1)) projects = projects.findFile(deel)?.takeIf { it.isDirectory } ?: return
        val werfMap = projects.findFile(delen.last())?.takeIf { it.isDirectory } ?: return
        val rijDoc = werfMap.findFile("mv3d-project.json") ?: return
        val rij = JSONObject(contentResolver.openInputStream(rijDoc.uri)?.use { it.readBytes().decodeToString() }
            ?: throw RuntimeException("mv3d-project.json kon niet gelezen worden"))
        val db = projects.findFile("project")?.takeIf { it.isFile }
            ?: throw RuntimeException("Nuwa heeft hier nog geen projectenlijst — start Nuwa één keer")

        val werk = java.io.File(cacheDir, "nuwa-" + System.currentTimeMillis()).apply { mkdirs() }
        try {
            val kopie = java.io.File(werk, "project")
            contentResolver.openInputStream(db.uri)?.use { inn -> kopie.outputStream().use { inn.copyTo(it) } }
                ?: throw RuntimeException("de projectenlijst kon niet gelezen worden")
            val wal = projects.findFile("project-wal")?.takeIf { it.isFile && it.length() > 0 }
            if (wal != null) contentResolver.openInputStream(wal.uri)?.use { inn -> java.io.File(werk, "project-wal").outputStream().use { inn.copyTo(it) } }

            val sql = android.database.sqlite.SQLiteDatabase.openDatabase(
                kopie.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            try {
                val naam = rij.getString("ProjectName")
                sql.beginTransaction()
                try {
                    sql.delete("TbProject", "ProjectName = ?", arrayOf(naam))
                    val v = android.content.ContentValues()
                    v.put("ProjectName", naam)
                    v.put("Creator", rij.optString("Creator", "MV3D"))
                    v.put("DateTime", rij.optLong("DateTime", System.currentTimeMillis()))
                    v.put("CoordSystem", rij.getString("CoordSystem"))
                    v.put("LayerTemplate", rij.optString("LayerTemplate", ""))
                    v.put("RefBaseName", rij.optString("RefBaseName", "Base_0"))
                    v.put("RefBaseLat", rij.optDouble("RefBaseLat", 0.0))
                    v.put("RefBaseLon", rij.optDouble("RefBaseLon", 0.0))
                    v.put("RefBaseAlt", rij.optDouble("RefBaseAlt", 0.0))
                    v.put("UpdateTime", rij.optLong("UpdateTime", System.currentTimeMillis()))
                    v.put("RefBaseAntHGT", rij.optDouble("RefBaseAntHGT", 0.0))
                    if (sql.insert("TbProject", null, v) < 0) throw RuntimeException("de rij kwam er niet in")
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
                // Alles in het hoofdbestand: de -wal gaat niet mee terug.
                sql.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            } finally { sql.close() }

            contentResolver.openOutputStream(db.uri, "wt")?.use { uit -> kopie.inputStream().use { it.copyTo(uit) } }
                ?: throw RuntimeException("de projectenlijst kon niet teruggeschreven worden")
            projects.findFile("project-wal")?.delete()
            projects.findFile("project-shm")?.delete()
        } finally {
            werk.deleteRecursively()
        }
    }

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
        val ymls = ArrayList<Triple<String, DocumentFile, Long>>()
        val ontwerpen = ArrayList<Triple<String, DocumentFile, Long>>()
        fun loop(dir: DocumentFile, prefix: String, diepte: Int) {
            if (diepte > 5 || files.length() >= 800) return
            for (f in dir.listFiles()) {
                val nm = f.name ?: continue
                val rel = if (prefix.isEmpty()) nm else "$prefix/$nm"
                if (f.isDirectory) loop(f, rel, diepte + 1)
                else {
                    files.put(JSONObject().put("path", rel).put("size", f.length()).put("m", f.lastModified()))
                    // Elke Unicontrol-werf draagt er een, en er staat in waar ze ligt. We pikken ze
                    // hier op omdat we hier tóch al langslopen; het lezen gebeurt straks, en alleen
                    // voor de werven waarvan we het antwoord nog niet hebben.
                    if (nm.equals("Project.yml", ignoreCase = true) && prefix.isNotEmpty()) {
                        ymls.add(Triple(prefix, f, f.lastModified()))
                    }
                    // Bij CHCnav staat de plek nergens apart, maar wel in het ontwerp: een LandXML onder
                    // <werf>/DesignData/<ontwerp>/. Eén per werf is genoeg — het is een speld, geen meting.
                    if (nm.endsWith(".xml", ignoreCase = true) && "/$prefix/".contains("/DesignData/")) {
                        val werf = prefix.substringBefore("/DesignData")
                        if (werf.isNotEmpty() && !prefix.startsWith("DesignData") && ontwerpen.none { it.first == werf }) {
                            ontwerpen.add(Triple(werf, f, f.lastModified()))
                        }
                    }
                }
            }
        }
        loop(tree, "", 0)
        // De werven: de bovenste mappen met iets erin. Meer kennis heeft het scherm niet nodig — de
        // server kijkt zelf welke bestanden er bij horen.
        werven = run {
            val namen = LinkedHashSet<String>()
            for (i in 0 until files.length()) {
                val pad = files.getJSONObject(i).optString("path")
                val eerste = pad.substringBefore('/')
                if (eerste.isNotEmpty() && eerste != pad) namen.add(eerste)
            }
            namen.toList()
        }
        // De naam van de map én waar ze staat.
        //
        // Alleen de naam is niet genoeg gebleken: op één toestel stonden er twee mappen die
        // allebei "CloudProjects" heten, en dan lees je in het portaal een lijst die je op de
        // tablet nergens terugvindt. Het volledige adres zegt welke van de twee het is.
        JSONObject()
            .put("root", tree.name ?: "")
            .put("rootUri", tree.uri.toString())
            .put("files", files)
            .put("plekken", plekken(ymls))
            .put("ontwerpplekken", ontwerpplekken(ontwerpen))
    } catch (_: Exception) { null }

    /** Wat we al uit een ontwerp gelezen hebben: pad → (datum van dat bestand, noord en oost). */
    private val ontwerpGeheugen = HashMap<String, Pair<Long, Pair<Double, Double>?>>()

    /**
     * Het eerste punt van het ontwerp van elke CHC-werf, ruw.
     *
     * `<P id="1">noord oost hoogte</P>` — de volgorde van LandXML. Welk stelsel dat is en of het ergens
     * op slaat, beslist de server: die kent de stelsels en ziet het .crd in de mappenlijst. Hier gaan
     * alleen de twee getallen mee.
     *
     * Alleen het begin van het bestand: een oppervlak telt tienduizenden punten en het eerste staat
     * vooraan. Wie verder moet zoeken, heeft geen oppervlak — dan geen speld.
     */
    private fun ontwerpplekken(lijst: List<Triple<String, DocumentFile, Long>>): JSONArray {
        val uit = JSONArray()
        var gelezen = 0
        for ((map, doc, datum) in lijst) {
            val sleutel = map + "|" + (doc.name ?: "")
            val onthouden = ontwerpGeheugen[sleutel]
            val punt = if (onthouden != null && onthouden.first == datum) onthouden.second else {
                if (gelezen >= 40) continue
                gelezen++
                val p = try { eerstePunt(doc) } catch (_: Exception) { null }
                ontwerpGeheugen[sleutel] = Pair(datum, p)
                p
            }
            if (punt != null) uit.put(JSONObject().put("map", map).put("n", punt.first).put("e", punt.second))
        }
        return uit
    }

    private val EERSTE_P = Regex("<P\\b[^>]*>\\s*(-?[0-9][0-9.eE+-]*)\\s+(-?[0-9][0-9.eE+-]*)")

    private fun eerstePunt(doc: DocumentFile): Pair<Double, Double>? {
        val kop = contentResolver.openInputStream(doc.uri)?.use { inn ->
            val buf = ByteArray(256 * 1024)
            var n = 0
            while (n < buf.size) { val r = inn.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
            String(buf, 0, n, Charsets.UTF_8)
        } ?: return null
        val m = EERSTE_P.find(kop) ?: return null
        val noord = m.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val oost = m.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return Pair(noord, oost)
    }

    /**
     * Waar liggen die werven?
     *
     * Het portaal kan de namen tonen maar niet de plek, en een werf zonder plek staat niet op een
     * kaart. Dat getal staat nochtans op deze tablet: elke Unicontrol-werf heeft een `Project.yml`
     * met daarin de laatst bekende plek van de machine en het stelsel waarin ze rekent.
     *
     * Wat we meesturen zijn vier waarden per werf, geen bestanden: de map, x, z en de naam van het
     * stelsel. Omrekenen naar breedte en lengte gebeurt op de server — daar staat de tabel met de
     * stelsels, en die hoort niet twee keer te bestaan.
     *
     * ── waarom x en z en niet x en y ──
     *
     * Unicontrol rekent zoals een spelmotor: y wijst omhoog. Het grondvlak is x/z. Nagemeten op een
     * project uit 2022 in Athus — x 254490, y 39,75, z 27943 — en dat valt in Lambert 72 precies op
     * Athus. Wie x en y neemt, komt in de Noordzee uit.
     */
    private fun plekken(ymls: List<Triple<String, DocumentFile, Long>>): JSONArray {
        val uit = JSONArray()
        var gelezen = 0
        for ((map, doc, datum) in ymls) {
            // Niet alleen de bovenste laag. Waar de werven staan hangt af van welke map de
            // machinist aangewezen heeft: wijst hij CloudProjects aan, dan ligt een werf één laag
            // diep; wijst hij de map erboven aan, dan twee. We sturen het volledige pad mee en
            // laten de server de naam eruit halen — die weet toch al waar de werven beginnen.
            if (map.count { it == '/' } > 3) continue

            // Al gelezen en niets veranderd? Dan niet opnieuw. Een tablet met tientallen werven
            // hangt aan een werf-4G en doet dit elke ronde; één keer lezen is genoeg.
            val onthouden = plekGeheugen[map]
            val plek = if (onthouden != null && onthouden.first == datum) onthouden.second else {
                if (gelezen >= 80) continue
                gelezen++
                val p = leesPlek(doc)
                plekGeheugen[map] = Pair(datum, p)
                p
            }
            if (plek != null) uit.put(JSONObject().put("map", map).put("x", plek.x).put("z", plek.z).put("cs", plek.cs))
        }
        return uit
    }

    /** De laatst bekende plek uit één Project.yml, of null als er niets bruikbaars in staat. */
    private fun leesPlek(doc: DocumentFile): Plek? = try {
        // Een Project.yml is een paar honderd bytes. Staat er meer, dan is het iets anders en
        // lezen we het niet — een tablet hoort geen megabytes te lezen voor een speld.
        if (doc.length() > 64 * 1024) null else {
            val tekst = contentResolver.openInputStream(doc.uri)?.use { it.readBytes().decodeToString() } ?: ""
            val regels = tekst.split('\n')

            // `x:` staat er twee keer: onder LastKnownPosition en onder SimulatorPosition. Die
            // tweede is de plek van een demo en heeft niets met de werf te maken — op een echt
            // bestand stond daar 427330/1108260, wat nergens in België ligt. Dus zoeken we vanaf
            // de regel LastKnownPosition en niet in het hele bestand.
            val start = regels.indexOfFirst { it.trim().startsWith("LastKnownPosition:") }
            if (start < 0) null else {
                val venster = regels.drop(start + 1).take(6)
                val x = getal(venster, "x")
                val z = getal(venster, "z")

                // En het stelsel. `RadioCoordinateSystem` staat er ook en is iets anders, dus de
                // regel moet er precies mee beginnen.
                val cs = regels.firstOrNull { it.trim().startsWith("CoordinateSystem:") }
                    ?.substringAfter(':')?.trim()?.trim('\'', '"') ?: ""

                // Een werf die nooit geopend is, draagt een plek van nul — of van een paar meter,
                // want dan staat het ontwerp in een plaatselijk stelsel. Gemeten op echte werven:
                // 0,0008 / 0,0094 en −6,34 / −14,51 en 231 / 74. Geen enkel landelijk stelsel komt
                // onder de duizend uit, dus dat is de grens. Zo'n werf blijft gewoon in de lijst
                // staan, alleen zonder speld.
                if (x == null || z == null || cs.isEmpty()) null
                else if (kotlin.math.abs(x) < 1000 || kotlin.math.abs(z) < 1000) null
                else Plek(x, z, cs)
            }
        }
    } catch (_: Exception) { null }

    /** `    x: 254490.5931` → 254490.5931 */
    private fun getal(regels: List<String>, sleutel: String): Double? =
        regels.firstOrNull { it.trim().startsWith("$sleutel:") }
            ?.substringAfter(':')?.trim()?.toDoubleOrNull()

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
