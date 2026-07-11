package be.mv3d.tablet

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RemoteFile(val id: String, val name: String, val url: String, val subfolder: String?)
data class Command(
    val id: String, val kind: String, val path: String?, val newPath: String?,
    val fileName: String?, val uploadUrl: String?, val downloadUrl: String?
)
data class SyncResult(val files: List<RemoteFile>, val guidance: String?, val commands: List<Command>)

/** Dunne client rond de bestaande MV3D device-API (/api/machines/sync en /tunnel). */
class Api(private val server: String, private val code: String) {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POST /api/machines/sync — heartbeat + mappenlijst + GPS; geeft bestanden + commando's terug. */
    fun sync(listing: JSONArray?, lat: Double?, lon: Double?, acc: Float?): SyncResult {
        val body = JSONObject().put("connection_code", code)
        if (listing != null) body.put("listing", listing)
        if (lat != null && lon != null) { body.put("latitude", lat); body.put("longitude", lon); if (acc != null) body.put("accuracy", acc.toDouble()) }
        val req = Request.Builder().url("$server/api/machines/sync")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw RuntimeException("sync ${resp.code}: $txt")
            val o = JSONObject(txt)
            val files = ArrayList<RemoteFile>()
            o.optJSONArray("files")?.let { for (i in 0 until it.length()) { val f = it.getJSONObject(i); files.add(RemoteFile(f.optString("id"), f.optString("name"), f.optString("url"), f.optString("subfolder").ifEmpty { null })) } }
            val cmds = ArrayList<Command>()
            o.optJSONArray("commands")?.let { for (i in 0 until it.length()) { val c = it.getJSONObject(i); cmds.add(Command(c.optString("id"), c.optString("kind"), c.optString("path").ifEmpty { null }, c.optString("new_path").ifEmpty { null }, c.optString("file_name").ifEmpty { null }, c.optString("upload_url").ifEmpty { null }, c.optString("download_url").ifEmpty { null })) } }
            return SyncResult(files, o.optString("guidance_system").ifEmpty { null }, cmds)
        }
    }

    /** PATCH /api/machines/sync — bevestig gesyncte bestanden + commando-resultaten. */
    fun confirm(transferIds: List<String>, results: List<Pair<String, String?>>) {
        val body = JSONObject().put("connection_code", code)
        if (transferIds.isNotEmpty()) body.put("transfer_ids", JSONArray(transferIds))
        if (results.isNotEmpty()) {
            val arr = JSONArray()
            results.forEach { (id, err) -> arr.put(JSONObject().put("id", id).put("status", if (err == null) "done" else "failed").apply { if (err != null) put("error", err) }) }
            body.put("command_results", arr)
        }
        val req = Request.Builder().url("$server/api/machines/sync")
            .patch(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().close()
    }

    /** POST /api/machines/tunnel — meld (of wis met null) de VNC-tunnel-URL + per-sessie wachtwoord. */
    fun tunnel(url: String?, password: String? = null) {
        val body = JSONObject().put("connection_code", code).put("tunnel_url", url ?: JSONObject.NULL)
        if (password != null) body.put("vnc_password", password)
        val req = Request.Builder().url("$server/api/machines/tunnel")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().close()
    }

    /** Download bytes (signed URL). */
    fun download(url: String): ByteArray {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("download ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Upload bytes naar een signed upload-URL (pull-commando). */
    fun upload(url: String, bytes: ByteArray) {
        val req = Request.Builder().url(url).put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw RuntimeException("upload ${it.code}") }
    }
}
