package be.mv3d.tablet

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Eén bestand dat op de tablet moet komen, met de map waarin het hoort. */
data class RemoteFile(val id: String, val name: String, val url: String, val subfolder: String?)

/**
 * Eén bestand dat het portaal van deze tablet wil hebben.
 *
 * De andere richting dan de rest: hier gaat er iets ván de tablet weg. Alleen op vraag — het
 * portaal zet een opdracht klaar, wij voeren ze uit. De koppeling waar het heen mag, komt mee.
 */
data class PullFile(val id: String, val path: String, val url: String, val token: String)

/** Wat er van één opdracht terechtkwam. */
data class PullResult(val id: String, val ok: Boolean, val bytes: Long, val error: String?)

/** Wat de server terugstuurt bij een ronde. */
data class SyncResult(
    val files: List<RemoteFile>,
    val guidance: String?,
    val name: String?,
    val pull: List<PullFile> = emptyList(),
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
            return SyncResult(
                files,
                o.optString("guidance_system").ifEmpty { null },
                o.optString("name").ifEmpty { null },
                pull,
            )
        }
    }

    /**
     * PATCH /api/machines/sync — pas als dit gelukt is, is een bestand van de wachtrij af.
     *
     * De opgestuurde bestanden gaan in dezelfde beweging mee. Mislukt er één, dan hoort dat erbij
     * te staan: een opdracht die blijft hangen op "bezig" ziet eruit als een tablet die niet
     * antwoordt, terwijl het bestand gewoon weg was.
     */
    fun confirm(transferIds: List<String>, pulled: List<PullResult> = emptyList()) {
        if (transferIds.isEmpty() && pulled.isEmpty()) return
        val body = JSONObject().put("connection_code", code)
        if (transferIds.isNotEmpty()) body.put("transfer_ids", org.json.JSONArray(transferIds))
        if (pulled.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for (p in pulled) {
                val o = JSONObject().put("id", p.id).put("ok", p.ok)
                if (p.ok) o.put("bytes", p.bytes) else o.put("error", p.error ?: "onbekend")
                arr.put(o)
            }
            body.put("pulled", arr)
        }
        val req = Request.Builder().url("$server/api/machines/sync")
            .patch(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().close()
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
     * Klopt deze code?
     *
     * Bij het koppelen willen we het meteen weten. Een tablet die "gekoppeld" zegt en daarna
     * dagenlang niets binnenhaalt omdat er een cijfer verkeerd stond, is erger dan een tablet die
     * meteen zegt dat de code niet klopt.
     */
    fun verifyCode(): Boolean = try { sync(null); true } catch (_: Exception) { false }

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
