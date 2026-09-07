package be.mv3d.tablet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * De app buiten de batterijbesparing houden.
 *
 * Android legt apps stil die het niet nodig vindt. Voor de meeste apps is dat goed; voor deze
 * niet. Een tablet in een cabine staat uren stil met het scherm uit, en juist dan hoort de werf
 * die je vanuit kantoor stuurt binnen te komen. Ligt de app te slapen, dan staat ze er 's morgens
 * niet — en niemand kan zien waarom, want er is niets misgegaan.
 *
 * Samsung is hier het strengst in: die zet apps die je een paar dagen niet opent in een lijst
 * "slapende apps", en dan stopt ook een voorgronddienst.
 *
 * De uitzondering vragen kost één tik. We vragen ze één keer, en we vragen ze niet opnieuw als ze
 * al gegeven is — een app die blijft zeuren om een toestemming, leert je op "nee" te duwen.
 */
object Batterij {

    /** Mag de app blijven draaien, of legt Android haar stil? */
    fun magDoorlopen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return try { pm.isIgnoringBatteryOptimizations(ctx.packageName) } catch (_: Exception) { true }
    }

    /**
     * Het venster waarin hij de uitzondering geeft.
     *
     * Eerst het rechtstreekse verzoek: dat is één tik op "Toestaan". Kent het toestel dat niet —
     * sommige merken hebben het eruit gehaald — dan openen we de lijst met batterij-instellingen.
     * Dat is een tik meer, maar het is beter dan een knop die niets doet.
     */
    fun vraag(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (magDoorlopen(ctx)) return
        val rechtstreeks = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(rechtstreeks); return } catch (_: Exception) { }

        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) { }
    }
}
