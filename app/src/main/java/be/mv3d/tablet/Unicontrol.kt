package be.mv3d.tablet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * De map van Unicontrol zelf terugvinden.
 *
 * Android laat een app niet zomaar in de opslag rondkijken; er moet een map aangewezen worden.
 * Maar de mappenkiezer kan wél vooraf op een plek gezet worden, en die plek kennen we: Unicontrol
 * zet zijn projecten altijd op dezelfde soort pad.
 *
 * Wat dat scheelt: de machinist tikt zijn code in en duwt één keer op "Deze map gebruiken". Hij
 * hoeft niet te weten waar Unicontrol zijn spullen bewaart, en hij kan er ook niet naast tikken —
 * de kiezer staat al op de juiste map open.
 *
 * Vinden we een map die we al mogen lezen (bij een tweede installatie is de toestemming er soms
 * nog), dan slaan we die stap zelfs helemaal over.
 */
object Unicontrol {

    /** Waar Unicontrol zijn projecten neerzet, in volgorde van hoe vaak we het zagen. */
    private val PADEN = listOf(
        "Unicontrol/Projects",
        "Unicontrol/CloudProjects",
        "Unicontrol",
        "Documents/Unicontrol/Projects",
        "Android/data/com.unicontrol.app/files/Projects",
    )

    private const val OPSLAG = "com.android.externalstorage.documents"

    /** Het adres waarmee de mappenkiezer op deze plek opengaat. */
    private fun beginBij(pad: String): Uri? = try {
        DocumentsContract.buildDocumentUri(OPSLAG, "primary:$pad")
    } catch (_: Exception) { null }

    /**
     * Een map die we nu al mogen gebruiken.
     *
     * Bij een herinstallatie blijft een eerder gegeven toestemming soms staan. Dan hoeft er niets
     * gevraagd te worden — de app kan meteen aan het werk.
     */
    fun alGegeven(ctx: Context): Uri? {
        for (p in ctx.contentResolver.persistedUriPermissions) {
            if (!p.isReadPermission || !p.isWritePermission) continue
            val doc = try { DocumentFile.fromTreeUri(ctx, p.uri) } catch (_: Exception) { null }
            if (doc != null && doc.canWrite()) return p.uri
        }
        return null
    }

    /**
     * De mappenkiezer, al opengezet op de map van Unicontrol.
     *
     * Zit die map er niet — een andere versie, een geheugenkaart — dan opent de kiezer gewoon waar
     * hij anders ook zou openen. Beter een kiezer die net naast staat dan een app die weigert.
     */
    fun kiezer(): Intent {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            beginBij(PADEN.first())?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        return i
    }

    /**
     * Ziet deze map eruit als de map van Unicontrol?
     *
     * Alleen om het te kunnen zeggen op het scherm — niet om te weigeren. Wie zijn projecten
     * ergens anders bewaart, moet dat gewoon kunnen kiezen; wij zijn niet degene die weet hoe zijn
     * tablet is ingericht.
     */
    fun lijktJuist(ctx: Context, tree: Uri): Boolean = try {
        val doc = DocumentFile.fromTreeUri(ctx, tree)
        val naam = (doc?.name ?: "").lowercase()
        naam.contains("project") || naam.contains("unicontrol") ||
            (doc?.listFiles()?.any { it.isDirectory && it.name?.equals("CloudProjects", true) == true } ?: false)
    } catch (_: Exception) { false }
}
