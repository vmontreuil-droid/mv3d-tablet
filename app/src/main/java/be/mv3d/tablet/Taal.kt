package be.mv3d.tablet

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * De taal van de app, zelf te kiezen.
 *
 * Tot nu volgde de app de taal van het toestel. Maar een tablet in een kraan staat vaak in de taal van
 * wie hem ingesteld heeft, niet van wie erin zit — gevraagd: "kun je de talenkiezer ook bij de android
 * app plaatsen". Dezelfde vijf als in de Convertor en in MV3D Veld.
 *
 * In gewone SharedPreferences en niet in de DataStore van [Prefs]: de taal moet er zijn vóór er iets
 * getekend wordt (attachBaseContext), en daar kan niet op een Flow gewacht worden. Niets gekozen = de
 * taal van het toestel, zoals het altijd was.
 */
object Taal {
    val TALEN = listOf("nl", "fr", "en", "de", "es")
    private const val BESTAND = "mv3d_taal"
    private const val SLEUTEL = "taal"

    fun gekozen(ctx: Context): String? =
        ctx.getSharedPreferences(BESTAND, Context.MODE_PRIVATE).getString(SLEUTEL, null)?.takeIf { it in TALEN }

    fun zet(ctx: Context, taal: String) {
        if (taal !in TALEN) return
        ctx.getSharedPreferences(BESTAND, Context.MODE_PRIVATE).edit().putString(SLEUTEL, taal).commit()
    }

    /** De taal die er nu staat: de gekozen, anders die van het toestel als we ze spreken, anders Nederlands. */
    fun huidig(ctx: Context): String {
        gekozen(ctx)?.let { return it }
        val toestel = ctx.resources.configuration.locales.get(0)?.language ?: "nl"
        return if (toestel in TALEN) toestel else "nl"
    }

    /** Een context die in de gekozen taal spreekt; zonder keuze de context zoals hij was. */
    fun omhul(basis: Context): Context {
        val taal = gekozen(basis) ?: return basis
        val locale = Locale(taal)
        Locale.setDefault(locale)
        val config = Configuration(basis.resources.configuration)
        config.setLocale(locale)
        return basis.createConfigurationContext(config)
    }
}
