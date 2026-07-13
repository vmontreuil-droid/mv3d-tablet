package be.mv3d.tablet

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
data class SyncResult(val files: List<RemoteFile>, val guidance: String?, val name: String?, val commands: List<Command>)
/** Eén geconverteerd bestand. dir = "project" (→ Projects/<werf>/) of "coordsys" (→ CoordinateSystems/). */
data class ConvOut(val path: String, val text: String, val dir: String = "project")
data class ConvResult(val werf: String, val folder: String, val surfaces: Int, val lines: Int, val files: List<ConvOut>)
data class Proj(val name: String, val type: String)
data class Werf(val name: String, val address: String, val lat: Double?, val lon: Double?, val files: Int, val active: Boolean, val projecten: List<Proj> = emptyList())
data class Overview(val name: String, val guidance: String, val code: String, val lat: Double?, val lon: Double?, val werven: List<Werf>)

/** Dunne client rond de bestaande MV3D device-API (/api/machines/sync en /tunnel). */
class Api(private val server: String, private val code: String) {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POST /api/machines/sync — heartbeat + mappenlijst + GPS; geeft bestanden + commando's terug. */
    fun sync(listing: JSONObject?, lat: Double?, lon: Double?, acc: Float?, werven: JSONArray? = null): SyncResult {
        val body = JSONObject().put("connection_code", code)
        if (listing != null) body.put("listing", listing)
        if (lat != null && lon != null) { body.put("latitude", lat); body.put("longitude", lon); if (acc != null) body.put("accuracy", acc.toDouble()) }
        if (werven != null && werven.length() > 0) body.put("werven", werven)
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
            return SyncResult(files, o.optString("guidance_system").ifEmpty { null }, o.optString("name").ifEmpty { null }, cmds)
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

    /** POST /api/converter/unicontrol — bronbestanden → Unicontrol-project (xml+dxf+Project.yml). */
    fun convertUnicontrol(werf: String, sources: List<Pair<String, ByteArray>>, region: String = "BE"): ConvResult {
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
        mp.addFormDataPart("name", werf)
        for ((fname, bytes) in sources) mp.addFormDataPart("files", fname, bytes.toRequestBody("application/octet-stream".toMediaType()))
        val req = Request.Builder().url("$server/api/converter/unicontrol?code=$code&region=$region").post(mp.build()).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: "{}"
            if (!r.isSuccessful) throw RuntimeException((try { JSONObject(txt).optString("error") } catch (_: Exception) { "" }).ifBlank { "conversie mislukt (${r.code})" })
            val o = JSONObject(txt)
            val files = ArrayList<ConvOut>()
            o.optJSONArray("files")?.let { for (i in 0 until it.length()) { val f = it.getJSONObject(i); files.add(ConvOut(f.getString("path"), f.getString("text"), f.optString("dir", "project"))) } }
            return ConvResult(o.optString("werf"), o.optString("folder"), o.optInt("surfaces"), o.optInt("lines"), files)
        }
    }

    /** GET /api/realtime-config — Supabase-URL + anon-key voor het live websocket-kanaal. */
    fun realtimeConfig(): Pair<String, String>? {
        return try {
            http.newCall(Request.Builder().url("$server/api/realtime-config").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JSONObject(r.body?.string() ?: "{}")
                val url = o.optString("url"); val key = o.optString("anon_key")
                if (url.isBlank() || key.isBlank()) null else url to key
            }
        } catch (_: Exception) { null }
    }

    /** Supabase e-mail/wachtwoord-login → (access_token, e-mail). */
    fun login(email: String, password: String): Pair<String, String>? {
        val cfg = realtimeConfig() ?: return null
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val req = Request.Builder().url("${cfg.first}/auth/v1/token?grant_type=password")
            .addHeader("apikey", cfg.second).addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body?.string() ?: "{}")
            val token = o.optString("access_token")
            return if (token.isNotBlank()) token to (o.optJSONObject("user")?.optString("email") ?: email.trim()) else null
        }
    }

    /** Overzicht van de kraan (naam, systeem, GPS, werven) voor de machineapp. */
    fun overview(): Overview? {
        http.newCall(Request.Builder().url("$server/api/machines/overview?code=$code").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body?.string() ?: "{}")
            fun dbl(k: String): Double? = if (o.isNull(k)) null else o.optDouble(k).let { if (it.isNaN()) null else it }
            val werven = ArrayList<Werf>()
            o.optJSONArray("werven")?.let { arr -> for (i in 0 until arr.length()) { val w = arr.getJSONObject(i)
                fun wd(k: String): Double? = if (w.isNull(k)) null else w.optDouble(k).let { if (it.isNaN()) null else it }
                val projs = ArrayList<Proj>()
                w.optJSONArray("projecten")?.let { pa -> for (j in 0 until pa.length()) { val p = pa.getJSONObject(j); projs.add(Proj(p.optString("name"), p.optString("type"))) } }
                werven.add(Werf(w.optString("name"), w.optString("address"), wd("lat"), wd("lon"), w.optInt("files"), w.optBoolean("active"), projs)) } }
            return Overview(o.optString("name"), o.optString("guidance"), o.optString("code"), dbl("lat"), dbl("lon"), werven)
        }
    }

    /** Satelliet-luchtfoto (ArcGIS World Imagery) rond een GPS-punt → JPEG-bytes. */
    fun aerial(lat: Double, lon: Double, w: Int, h: Int): ByteArray? {
        val dLon = 0.006; val dLat = dLon * (h.toDouble() / w) * 0.62
        return aerialBbox(lon - dLon, lat - dLat, lon + dLon, lat + dLat, w, h)
    }

    /** Luchtfoto voor een expliciete bbox (west,south,east,north in WGS84) → JPEG-bytes. */
    fun aerialBbox(west: Double, south: Double, east: Double, north: Double, w: Int, h: Int): ByteArray? {
        val bbox = "$west,$south,$east,$north"
        val url = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/export?bbox=$bbox&bboxSR=4326&imageSR=3857&size=$w,$h&format=jpg&f=image"
        return try { http.newCall(Request.Builder().url(url).build()).execute().use { if (!it.isSuccessful) null else it.body?.bytes() } } catch (_: Exception) { null }
    }

    /** Kraancode controleren: bestaat de machine? (via /api/machines/sync). */
    fun verifyCode(): Boolean = try {
        val req = Request.Builder().url("$server/api/machines/sync")
            .post(JSONObject().put("connection_code", code).toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    /** Toegangscode → sessie: redeem-code (token_hash) + Supabase verify. */
    fun loginCode(accessCode: String): Pair<String, String>? {
        val rReq = Request.Builder().url("$server/api/worksmanager/redeem-code")
            .post(JSONObject().put("code", accessCode.trim().uppercase()).put("device", "tablet").toString().toRequestBody(json)).build()
        val hash = http.newCall(rReq).execute().use { r -> if (!r.isSuccessful) return null; JSONObject(r.body?.string() ?: "{}").optString("token_hash") }
        if (hash.isBlank()) return null
        val cfg = realtimeConfig() ?: return null
        val vReq = Request.Builder().url("${cfg.first}/auth/v1/verify")
            .addHeader("apikey", cfg.second).addHeader("Content-Type", "application/json")
            .post(JSONObject().put("type", "magiclink").put("token_hash", hash).toString().toRequestBody(json)).build()
        http.newCall(vReq).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body?.string() ?: "{}")
            val token = o.optString("access_token")
            return if (token.isNotBlank()) token to (o.optJSONObject("user")?.optString("email") ?: "") else null
        }
    }

    /** POST /api/machines/screen-frame — upload één JPEG-frame, krijg de wachtende input terug. */
    fun screenFrame(jpeg: ByteArray): JSONArray {
        val req = Request.Builder().url("$server/api/machines/screen-frame?code=$code")
            .post(jpeg.toRequestBody("image/jpeg".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return JSONArray()
            return JSONObject(r.body?.string() ?: "{}").optJSONArray("inputs") ?: JSONArray()
        }
    }

    /** POST /api/machines/screen-status — meld de schermdeel-status/fout (voor diagnose in het portaal). */
    fun screen(status: String) {
        val body = JSONObject().put("connection_code", code).put("status", status)
        val req = Request.Builder().url("$server/api/machines/screen-status")
            .post(body.toString().toRequestBody(json)).build()
        try { http.newCall(req).execute().close() } catch (_: Exception) {}
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
