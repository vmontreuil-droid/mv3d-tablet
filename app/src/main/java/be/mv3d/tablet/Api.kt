package be.mv3d.tablet

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Eén bestand dat op de tablet moet komen, met de map waarin het hoort. */
data class RemoteFile(val id: String, val name: String, val url: String, val subfolder: String?)

/** Wat de server terugstuurt bij een ronde. */
data class SyncResult(val files: List<RemoteFile>, val guidance: String?, val name: String?)

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
            return SyncResult(
                files,
                o.optString("guidance_system").ifEmpty { null },
                o.optString("name").ifEmpty { null },
            )
        }
    }

    /** PATCH /api/machines/sync — pas als dit gelukt is, is een bestand van de wachtrij af. */
    fun confirm(transferIds: List<String>) {
        if (transferIds.isEmpty()) return
        val body = JSONObject().put("connection_code", code)
        body.put("transfer_ids", org.json.JSONArray(transferIds))
        val req = Request.Builder().url("$server/api/machines/sync")
            .patch(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().close()
    }

    /**
     * Klopt deze code?
     *
     * Bij het koppelen willen we het meteen weten. Een tablet die "gekoppeld" zegt en daarna
     * dagenlang niets binnenhaalt omdat er een cijfer verkeerd stond, is erger dan een tablet die
     * meteen zegt dat de code niet klopt.
     */
    fun verifyCode(): Boolean = try { sync(null); true } catch (_: Exception) { false }

    /** Het bestand zelf. De koppeling komt uit sync() en is een kwartier geldig. */
    fun download(url: String): ByteArray {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("download ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }
}
