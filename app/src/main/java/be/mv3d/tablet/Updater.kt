package be.mv3d.tablet

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Auto-update via de publieke GitHub-releases van deze app. Checkt de laatste release,
 * vergelijkt met de eigen versionCode en biedt (indien nieuwer) een download+installatie aan.
 * Android laat geen stille herinstallatie toe voor een zij-geladen app → één tik "Installeren".
 */
object Updater {
    // publieke repo → geen token nodig
    private const val API = "https://api.github.com/repos/vmontreuil-droid/mv3d-tablet/releases/latest"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Update(val versionCode: Int, val versionName: String, val apkUrl: String)

    /** Geeft een Update terug als er een nieuwere versie online staat, anders null. */
    fun check(): Update? {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").header("User-Agent", "mv3d-tablet").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string() ?: return null)
            val tag = o.optString("tag_name")                 // "build-<n>"
            val latest = tag.substringAfterLast('-').toIntOrNull() ?: return null
            if (latest <= BuildConfig.VERSION_CODE) return null
            val assets = o.optJSONArray("assets") ?: return null
            var apk: String? = null
            for (i in 0 until assets.length()) { val a = assets.getJSONObject(i); if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break } }
            return apk?.let { Update(latest, o.optString("name").ifEmpty { tag }, it) }
        }
    }

    /** Download de APK naar de cache en start de systeem-installer (gebruiker tikt "Installeren"). */
    fun downloadAndInstall(ctx: Context, apkUrl: String) {
        val out = File(ctx.cacheDir, "mv3d-machine-update.apk")
        http.newCall(Request.Builder().url(apkUrl).header("User-Agent", "mv3d-tablet").build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("download ${r.code}")
            out.outputStream().use { fos -> r.body?.byteStream()?.copyTo(fos) }
        }
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
