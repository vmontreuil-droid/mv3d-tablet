package be.mv3d.tablet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * De map waar het programma van de klant zijn werven bewaart.
 *
 * Android laat een app niet zomaar in de opslag rondkijken; er moet een map aangewezen worden.
 * Maar de mappenkiezer kan wél vooraf op een plek gezet worden, en die plekken kennen we van de
 * programma's die we tegenkomen.
 *
 * Dit heette Unicontrol en kende alleen Unicontrol. Er zijn twee toestellen bijgekomen die niet in
 * een kraan zitten maar bij een landmeter: een TSC5 draait Android met Trimble Access erop. De
 * app zelf hoefde daar nauwelijks voor te veranderen — ze schrijft in de map die je aanwijst en
 * vraagt niet wat er draait — dus is alleen deze lijst algemener geworden.
 *
 * ── waarom raden hier wél mag ──
 *
 * Elders in dit project is een verzonnen pad de oorzaak van storingen geweest: een geoïde die niet
 * bestond, een coördinaatsysteem dat niet meekwam. Hier is dat anders, en het verschil is
 * wezenlijk: deze paden bepalen alleen wáár de kiezer opengaat. Er wordt nooit ergens geschreven
 * op grond van dit lijstje — dat gebeurt in de map die de gebruiker aanwijst. Staat er dus een pad
 * bij dat niet klopt, dan opent de kiezer een niveau hoger en duwt hij één keer extra. Meer niet.
 */
object Veldmap {

    /**
     * Waar de programma's die we kennen hun werven neerzetten.
     *
     * In volgorde van hoe vaak we ze zagen. De eerste is waar de kiezer op opent; de rest staat
     * erbij omdat een toestel er soms een van gebruikt en het scheelt niets om ze te noemen.
     */
    private val PADEN = listOf(
        // Unicontrol — nagemeten op een echte tablet: op het toestel staat het in CloudProjects,
        // op een USB-stick in Projects. Twee verschillende mappen voor hetzelfde merk.
        "Unicontrol/CloudProjects",
        "Unicontrol/Projects",
        "Unicontrol",
        "Documents/Unicontrol/Projects",
        "Android/data/com.unicontrol.app/files/Projects",
        // Trimble Access op Android (TSC5, TDC600). Deze paden zijn niet nagemeten — ze staan hier
        // als vertrekpunt voor de kiezer, niet als plek om te schrijven.
        "Trimble Data",
        "Documents/Trimble Data",
        "Android/data/com.trimble.access/files/Trimble Data",
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
     * De mappenkiezer, al opengezet op de eerste plek die we kennen.
     *
     * Zit die map er niet — een ander programma, een geheugenkaart — dan opent de kiezer gewoon
     * waar hij anders ook zou openen. Beter een kiezer die net naast staat dan een app die weigert.
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
     * Ziet deze map eruit als een map waar werven in horen?
     *
     * Alleen om het te kunnen zeggen op het scherm — niet om te weigeren. Wie zijn projecten
     * ergens anders bewaart, moet dat gewoon kunnen kiezen; wij zijn niet degene die weet hoe zijn
     * toestel is ingericht.
     */
    fun lijktJuist(ctx: Context, tree: Uri): Boolean = try {
        val doc = DocumentFile.fromTreeUri(ctx, tree)
        val naam = (doc?.name ?: "").lowercase()
        val kinderen = doc?.listFiles()?.mapNotNull { if (it.isDirectory) it.name?.lowercase() else null } ?: emptyList()
        naam.contains("project") || naam.contains("unicontrol") || naam.contains("trimble") ||
            kinderen.any { it == "cloudprojects" || it == "projects" }
    } catch (_: Exception) { false }
}
