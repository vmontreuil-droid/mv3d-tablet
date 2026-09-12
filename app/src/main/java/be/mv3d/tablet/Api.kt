package be.mv3d.tablet

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Wat de server zegt als dit toestel zich aanmeldt: zijn eigen code, en of kantoor die al
 * ingetikt heeft. De naam is die van de machine, als ze er al een heeft.
 */
data class Aanmelding(val code: String, val gekoppeld: Boolean, val naam: String?)

/** Eén bestand dat op de tablet moet komen, met de map waarin het hoort. */
data class RemoteFile(val id: String, val name: String, val url: String, val subfolder: String?)

/**
 * Eén bestand dat het portaal van deze tablet wil hebben.
 *
 * De andere richting dan de rest: hier gaat er iets ván de tablet weg. Alleen op vraag — het
 * portaal zet een opdracht klaar, wij voeren ze uit. De koppeling waar het heen mag, komt mee.
 */
data class PullFile(val id: String, val path: String, val url: String, val token: String)

/**
 * Eén bestand dat van de tablet af moet.
 *
 * Alleen een pad, en alleen een pad dat deze tablet zelf gemeld heeft: de server zeeft dat. Het
 * wordt gevraagd vanuit de Convertor, na een dubbele bevestiging.
 */
data class RemoveFile(val id: String, val path: String)

/**
 * Een werfmap die een andere naam krijgt.
 *
 * Het pad is de map zoals deze tablet ze meldde; `naar` is alleen de nieuwe naam van die map, geen
 * pad. Bij Unicontrol is de naam van de map de naam van de werf.
 */
data class RenameDir(val id: String, val path: String, val naar: String)

/** Wat er van één opdracht terechtkwam. */
data class PullResult(val id: String, val ok: Boolean, val bytes: Long, val error: String?)

/** Wat de server terugstuurt bij een ronde. */
data class SyncResult(
    val files: List<RemoteFile>,
    val guidance: String?,
    val name: String?,
    val pull: List<PullFile> = emptyList(),
    val remove: List<RemoveFile> = emptyList(),
    val hernoem: List<RenameDir> = emptyList(),
)

/**
 * De hele omgang met mv3d.be, in drie handelingen.
 *
 * Hier stond eerder van alles bij: aanmelden met een mailadres, werven ophalen, luchtfoto's,
 * schermbeelden versturen, een tunnel openen. Dat is er allemaal uit. De app doet één ding —
 * bestanden op de juiste plek zetten — en dan hoort deze klasse er ook maar drie te kennen:
 * vragen wat er klaarstaat, het ophalen, en zeggen dat het gelukt is.
 */
class Api(private val server: String, private val code: String) {

    // ── vóór er een code is ──
    //
    // Dit hoort niet bij een code, want er is er nog geen: het toestel vraagt er net één. Daarom
    // staat het los van de rest, zonder code in de constructor.
    companion object {
        /** Kort geduld. Dit loopt om de vijf seconden terwijl iemand naar het scherm staat te kijken. */
        private val kort by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /** Wat we op dit toestel vonden. Eén keer zoeken is genoeg zolang de app draait. */
        @Volatile private var gevonden: List<String>? = null

        /**
         * POST /api/machines/aanmelden — "ik ben er, welke code hoort bij mij, en heeft kantoor ze al?"
         *
         * Merk, model en de programma's gaan mee, zodat kantoor bij het toevoegen niets hoeft in te
         * vullen: wie de code intikt, ziet meteen welk toestel het is en wat erop draait.
         *
         * Alles wat misloopt — geen internet, een oudere server die dit adres nog niet kent, een
         * antwoord dat nergens op lijkt — geeft null. Het scherm blijft dan gewoon vragen, en de
         * weg met de hand blijft open. Hier mag niets vastlopen.
         */
        fun aanmelden(ctx: Context, server: String, installatie: String): Aanmelding? {
            return try {
                val toestel = JSONObject()
                    .put("merk", Build.MANUFACTURER ?: "")
                    .put("model", Build.MODEL ?: "")
                    .put("naam", toestelNaam(ctx))
                val body = JSONObject()
                    .put("installatie", installatie)
                    .put("app", "tablet")
                    .put("app_version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                    .put("toestel", toestel)
                    .put("programmas", JSONArray(programmas(ctx)))
                val req = Request.Builder().url("$server/api/machines/aanmelden")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                kort.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val o = JSONObject(resp.body?.string() ?: return null)
                    // Acht cijfers, of het is geen code. Een half antwoord op het scherm zetten is
                    // erger dan even niets: dan tikt kantoor een nummer in dat niet bestaat.
                    val code = o.optString("code")
                    if (!o.optBoolean("ok") || code.length != 8 || !code.all { it.isDigit() }) return null
                    Aanmelding(
                        code,
                        o.optBoolean("gekoppeld"),
                        if (o.isNull("naam")) null else o.optString("naam").ifBlank { null },
                    )
                }
            } catch (_: Exception) { null }
        }

        /** De naam die de eigenaar het toestel gaf, als Android die laat lezen; anders het model. */
        private fun toestelNaam(ctx: Context): String = try {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (_: Exception) { Build.MODEL }

        /**
         * Welke van de programma's die we kennen, op dit toestel staan — de belangrijkste eerst.
         *
         * We zoeken niet op een vaste pakketnaam, want die kennen we niet zeker: Unicontrol staat niet
         * in de Play Store, en Trimble noemt zijn pakketten niet overal hetzelfde. Wel op wat een
         * machinist ook ziet: de naam van het pakket of van het icoon. Een app van een ander merk die
         * toevallig "Unicontrol" heet (ayatec maakt er één) laten we liggen.
         *
         * Android 11 en later toont een app alleen de andere apps die ze in het manifest aankondigt;
         * daar staat daarom "alles wat een icoon heeft". Dat is geen toestemming, en veel minder
         * dan "alle pakketten".
         *
         * Het is een hint, geen waarheid. Vinden we niets, dan gaat er een lege lijst mee en raadt
         * de server uit het model — en kantoor kan het bij het toevoegen altijd nog rechtzetten.
         */
        @Suppress("DEPRECATION")
        private fun programmas(ctx: Context): List<String> {
            gevonden?.let { return it }
            val lijst = LinkedHashSet<String>()
            try {
                val pm = ctx.packageManager
                val hoofd = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val namen = pm.queryIntentActivities(hoofd, 0).map {
                    (it.activityInfo.packageName + " " + it.loadLabel(pm)).lowercase()
                }
                if (namen.any { it.contains("unicontrol") && !it.contains("ayatec") }) lijst.add("UNICONTROL")
                if (namen.any { it.contains("trimble") && it.contains("access") }) lijst.add("TRIMBLE_ACCESS")
                if (namen.any { it.contains("siteworks") }) lijst.add("TRIMBLE_SITEWORKS")
                if (namen.any { it.contains("scs900") }) lijst.add("TRIMBLE_SCS900")
            } catch (_: Exception) { return emptyList() }
            return lijst.toList().also { gevonden = it }
        }
    }

    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // een werf-4G is traag en een ontwerp is soms groot
        .build()

    /**
     * POST /api/machines/sync — "ik ben er, staat er iets voor mij klaar?"
     *
     * De mappenlijst gaat mee zodat het portaal kan tonen wat er op de tablet staat. Lukt het
     * opstellen daarvan niet, dan gaat de ronde toch door: bestanden ophalen is de hoofdzaak.
     */
    fun sync(listing: JSONObject?): SyncResult {
        val body = JSONObject().put("connection_code", code)
        if (listing != null) body.put("listing", listing)
        // Welke app en welke versie. Bij "hij doet raar" was dat altijd de eerste vraag, en het
        // antwoord moest van de machinist komen — die daarvoor uit zijn kraan moest klimmen.
        body.put("app", "tablet")
        body.put("app_version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        // ── wat deze versie kan ──
        //
        // De builds die al in cabines hangen, kunnen niet wissen en niet hernoemen. Kreeg zo'n tablet
        // toch zo'n opdracht, dan bleef die hangen en gebeurde er niets. Nu zegt de app het zelf, en
        // deelt de server die opdrachten alleen uit aan wie het meldt. Een oudere app hoort meteen
        // waarom niet.
        body.put("kan", org.json.JSONArray().put("wissen").put("hernoemen"))
        val req = Request.Builder().url("$server/api/machines/sync")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw RuntimeException("sync ${resp.code}: $txt")
            val o = JSONObject(txt)
            val files = ArrayList<RemoteFile>()
            o.optJSONArray("files")?.let {
                for (i in 0 until it.length()) {
                    val f = it.getJSONObject(i)
                    files.add(RemoteFile(
                        f.optString("id"), f.optString("name"), f.optString("url"),
                        f.optString("subfolder").ifEmpty { null },
                    ))
                }
            }
            // En wat het portaal van ons wil hebben.
            val pull = ArrayList<PullFile>()
            o.optJSONArray("pull")?.let {
                for (i in 0 until it.length()) {
                    val q = it.getJSONObject(i)
                    pull.add(PullFile(
                        q.optString("id"), q.optString("path"),
                        q.optString("url"), q.optString("token"),
                    ))
                }
            }
            // En wat er van de tablet af moet.
            val remove = ArrayList<RemoveFile>()
            o.optJSONArray("remove")?.let {
                for (i in 0 until it.length()) {
                    val q = it.getJSONObject(i)
                    val pad = q.optString("path")
                    if (pad.isNotBlank()) remove.add(RemoveFile(q.optString("id"), pad))
                }
            }
            // En welke werfmappen een andere naam krijgen.
            val hernoem = ArrayList<RenameDir>()
            o.optJSONArray("hernoem")?.let {
                for (i in 0 until it.length()) {
                    val q = it.getJSONObject(i)
                    val pad = q.optString("path")
                    val naar = q.optString("naar")
                    if (pad.isNotBlank() && naar.isNotBlank()) hernoem.add(RenameDir(q.optString("id"), pad, naar))
                }
            }
            return SyncResult(
                files,
                o.optString("guidance_system").ifEmpty { null },
                o.optString("name").ifEmpty { null },
                pull,
                remove,
                hernoem,
            )
        }
    }

    /**
     * PATCH /api/machines/sync — pas als dit gelukt is, is een bestand van de wachtrij af.
     *
     * De opgestuurde, gewiste en hernoemde dingen gaan in dezelfde beweging mee. Mislukt er één,
     * dan hoort dat erbij te staan: een opdracht die blijft hangen op "bezig" ziet eruit als een
     * tablet die niet antwoordt, terwijl er gewoon iets misliep.
     */
    fun confirm(
        transferIds: List<String>,
        pulled: List<PullResult> = emptyList(),
        removed: List<PullResult> = emptyList(),
        hernoemd: List<PullResult> = emptyList(),
    ) {
        if (transferIds.isEmpty() && pulled.isEmpty() && removed.isEmpty() && hernoemd.isEmpty()) return
        val body = JSONObject().put("connection_code", code)
        if (transferIds.isNotEmpty()) body.put("transfer_ids", org.json.JSONArray(transferIds))
        if (pulled.isNotEmpty()) body.put("pulled", uitslagen(pulled))
        if (removed.isNotEmpty()) body.put("removed", uitslagen(removed))
        if (hernoemd.isNotEmpty()) body.put("hernoemd", uitslagen(hernoemd))
        val req = Request.Builder().url("$server/api/machines/sync")
            .patch(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().close()
    }

    private fun uitslagen(lijst: List<PullResult>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (p in lijst) {
            val o = JSONObject().put("id", p.id).put("ok", p.ok)
            if (p.ok) o.put("bytes", p.bytes) else o.put("error", p.error ?: "onbekend")
            arr.put(o)
        }
        return arr
    }

    /**
     * Een bestand van de tablet naar de opslag sturen, met de koppeling die de server meegaf.
     *
     * Met een straaltje, om dezelfde reden als bij het binnenhalen: een lijnenplan van een parking
     * is tweehonderd megabyte, en de goedkope tablets in een cabine hebben dat geheugen niet.
     */
    fun upload(url: String, token: String, lengte: Long, lees: () -> java.io.InputStream): Long {
        val lichaam = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = lengte
            override fun writeTo(sink: okio.BufferedSink) {
                lees().use { bron ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = bron.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                    }
                }
            }
        }
        val req = Request.Builder().url(url)
            .header("authorization", "Bearer $token")
            .header("x-upsert", "true")
            .put(lichaam).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("opsturen ${r.code}")
        }
        return lengte
    }

    /**
     * De uitslag van het nakijken van een koppelcode.
     *
     * Bij het koppelen willen we het meteen weten: een tablet die "gekoppeld" zegt en daarna
     * dagenlang niets binnenhaalt omdat er een cijfer verkeerd stond, is erger dan een tablet die
     * meteen zegt dat de code niet klopt.
     *
     * Maar er waren twee uitkomsten waar er drie horen. Het scherm maakte van "niet goed" steevast
     * "Die code kennen we niet. Kijk hem na in de MV3D Convertor." In een cabine zonder 4G tikte de
     * machinist dus de júíste code in en kreeg te horen dat hij fout was — waarna hij hem opnieuw
     * intikte, en opnieuw.
     *
     * GOED · FOUT (de server zegt nee) · ONBEKEND (we konden er niet bij).
     */
    enum class CodeUitslag { GOED, FOUT, ONBEKEND }

    fun codeNakijken(): CodeUitslag = try {
        sync(null); CodeUitslag.GOED
    } catch (e: Exception) {
        // Een antwoord van de server met een foutcode betekent dat de code werkelijk niet deugt.
        // Alles wat het netwerk zelf is — geen bereik, tijd verlopen, dns — zegt niets over de code.
        if ((e.message ?: "").startsWith("sync ")) CodeUitslag.FOUT else CodeUitslag.ONBEKEND
    }

    /**
     * Het bestand zelf, rechtstreeks de map in. De koppeling komt uit sync() en is een kwartier geldig.
     *
     * Het gaat met een straaltje en niet in één hap. Hier stond `body.bytes()`, en dat zet het hele
     * bestand in het geheugen van de tablet — een lijnenplan van een parking is tweehonderd
     * megabyte, en de goedkope tablets in een cabine hebben dat niet. Dan valt het om met een
     * OutOfMemory, en dat leest op het scherm als "er is niets doorgekomen".
     */
    fun download(url: String, uit: OutputStream) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("download ${r.code}")
            val body = r.body ?: throw RuntimeException("download: leeg antwoord")
            body.byteStream().use { it.copyTo(uit, 64 * 1024) }
        }
    }
}
