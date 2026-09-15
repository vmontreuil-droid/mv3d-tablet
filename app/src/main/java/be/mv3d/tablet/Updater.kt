package be.mv3d.tablet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bijwerken van de app zelf, via de publieke releases van deze repo.
 *
 * ── waarom het in twee stappen gaat ──
 *
 * Hier stond één handeling: kijken, downloaden en meteen de installer openen. En ze werd
 * aangeroepen bij het openen van de app. Voor deze app is dat allebei verkeerd.
 *
 * Verkeerd, omdat dit de app is die je één keer koppelt en daarna nooit meer aanraakt. Ze draait
 * maandenlang op de achtergrond in een cabine; niemand opent haar. Een controle die alleen bij
 * het openen loopt, loopt dus nooit — en dan blijft een tablet op een versie van een half jaar
 * oud staan zonder dat iemand het merkt.
 *
 * En verkeerd, omdat een installatievenster dat vanzelf opengaat terwijl er gegraven wordt, in de
 * weg staat. De dienst kijkt nu één keer per dag, zet de nieuwe versie stil klaar, en legt een
 * melding in de balk. De machinist tikt erop wanneer het hem uitkomt.
 *
 * Die ene tik blijft. Android laat een zij-geladen app niet stil herinstalleren; daar komen we
 * alleen onderuit met een beheerde tablet of de Play Store.
 */
object Updater {
    // publieke repo → geen sleutel nodig
    private const val API = "https://api.github.com/repos/vmontreuil-droid/mv3d-tablet/releases/latest"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Eén tegelijk. De dienst en het scherm kunnen allebei kijken; twee keer downloaden is zonde. */
    @Volatile private var bezig = false

    data class Update(val versionCode: Int, val versionName: String, val apkUrl: String)

    /** Staat er een nieuwere versie online? Anders null. */
    fun check(): Update? {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "mv3d-tablet")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string() ?: return null)
            val tag = o.optString("tag_name")                 // "build-<n>"
            val latest = tag.substringAfterLast('-').toIntOrNull() ?: return null
            if (latest <= BuildConfig.VERSION_CODE) return null
            val assets = o.optJSONArray("assets") ?: return null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
            }
            return apk?.let { Update(latest, o.optString("name").ifEmpty { tag }, it) }
        }
    }

    /** Het bestand waarin build <n> komt te staan. */
    fun bestand(ctx: Context, versionCode: Int) = File(ctx.cacheDir, "mv3d-machine-$versionCode.apk")

    /**
     * De klaargezette apk's weghalen.
     *
     * Voor na een geslaagde bijwerking: dat bestand is dan honderd megabyte die niets meer doet,
     * op een tablet die er weinig heeft. En zolang het er ligt, denkt de app dat er nog iets klaar
     * staat.
     */
    fun ruimOp(ctx: Context) {
        try {
            ctx.cacheDir.listFiles()?.forEach { if (it.name.startsWith("mv3d-machine-")) it.delete() }
        } catch (_: Exception) { }
    }

    /** Staat build <n> al klaar? Een half binnengehaald bestand telt niet mee. */
    fun staatKlaar(ctx: Context, versionCode: Int): Boolean {
        val f = bestand(ctx, versionCode)
        return f.exists() && f.length() > 100_000
    }

    /**
     * Binnenhalen, zonder iets te openen.
     *
     * Naar een bestand ernaast en pas op het eind hernoemen: een download die halverwege afbreekt
     * — en op een werf breekt er van alles halverwege af — laat anders een stuk apk achter dat er
     * klaar uitziet en het niet is. De vorige versies gaan weg; die hoeven geen plaats te houden
     * op een tablet die er weinig van heeft.
     */
    fun haal(ctx: Context, u: Update): Boolean {
        if (bezig) return false
        bezig = true
        try {
            if (staatKlaar(ctx, u.versionCode)) return true
            val doel = bestand(ctx, u.versionCode)
            val half = File(ctx.cacheDir, "mv3d-machine-${u.versionCode}.deel")
            http.newCall(Request.Builder().url(u.apkUrl).header("User-Agent", "mv3d-tablet").build()).execute().use { r ->
                if (!r.isSuccessful) throw RuntimeException("download ${r.code}")
                half.outputStream().use { uit -> r.body?.byteStream()?.copyTo(uit, 64 * 1024) }
            }
            if (half.length() < 100_000) { half.delete(); return false }
            doel.delete()
            if (!half.renameTo(doel)) { half.delete(); return false }

            ctx.cacheDir.listFiles()?.forEach {
                if (it.name.startsWith("mv3d-machine-") && it.name != doel.name) it.delete()
            }
            return true
        } finally { bezig = false }
    }

    /**
     * Stil installeren — alleen als wij eigenaar van het toestel zijn.
     *
     * Dit is de enige weg waarop een zij-geladen app zichzelf zonder tik kan bijwerken. Zie
     * Beheerder.kt: het vraagt één keer adb per tablet, en daarna nooit meer iets.
     *
     * De app wordt hierbij vervangen terwijl ze draait. Android stopt haar dan en start haar
     * opnieuw; de dienst komt vanzelf terug (RECEIVE_BOOT_COMPLETED en de herstart van de
     * voorgrondddienst). Er gaat geen werf verloren: wat binnengehaald was staat al op schijf, en
     * wat nog in de wachtrij stond wordt bij de volgende ronde opnieuw opgehaald.
     *
     * Geeft terug of het gelukt is te STARTEN. Of de installatie zelf slaagt weten we hier niet —
     * dat komt later binnen, en als het misgaat blijft de melding staan en kan de machinist het
     * alsnog met de hand doen. Stil falen mag niet: dan draait de vloot maanden op een oude
     * versie zonder dat iemand het ziet.
     */
    fun installeerStil(ctx: Context, versionCode: Int): Boolean {
        if (!Beheerder.isEigenaar(ctx)) return false
        val apk = bestand(ctx, versionCode)
        if (!apk.exists() || apk.length() < 100_000) return false
        return try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("mv3d", 0, apk.length()).use { uit ->
                    apk.inputStream().use { it.copyTo(uit, 64 * 1024) }
                    s.fsync(uit)
                }
                // Waar het antwoord heen mag. We doen er niets mee behalve het niet laten vallen:
                // zonder een ontvanger weigert Android de sessie.
                val heen = Intent(ctx, MainActivity::class.java)
                val vlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
                s.commit(PendingIntent.getActivity(ctx, 0, heen, vlag).intentSender)
            }
            true
        } catch (_: Exception) { false }
    }

    /** De installer van Android openen. Hier tikt de machinist "Installeren". */
    fun installatie(ctx: Context, versionCode: Int): Intent {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", bestand(ctx, versionCode))
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Bijwerken. Stil als het kan, met een tik als het moet.
     *
     * De volgorde is niet vrijblijvend: lukt het stille pad niet — geen eigenaar, een toestel dat
     * het weigert — dan moet de gewone weg er nog zijn. Anders zou een tablet die niet als
     * eigenaar gezet is helemaal niet meer bijwerken, en dat is slechter dan waar we vandaan komen.
     */
    fun installeer(ctx: Context, versionCode: Int) {
        if (installeerStil(ctx, versionCode)) return
        try { ctx.startActivity(installatie(ctx, versionCode)) } catch (_: Exception) { }
    }
}
