package be.mv3d.tablet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Waar staat dit toestel?
 *
 * Gevraagd (16/9/2026): "kan ik eventueel zien waar alles zich bevindt, uit veiligheidsredenen — als
 * er iets gestolen is bijvoorbeeld", "ook naar service toe kan dit handig zijn", en "aan die locatie
 * kunnen we weten of de juiste coördinatenstelsels worden gebruikt".
 *
 * Wat we doen en niet doen:
 *
 *   · Alleen de laatst gekende plek, en die gaat bij de gewone ronde mee naar de server. Geen spoor,
 *     geen geschiedenis: de server bewaart één plek per toestel en overschrijft ze (AVG — het gaat om
 *     de machine, niet om wie erin zit).
 *   · Geen eigen gps aanzetten en laten lopen. We vragen wat Android al weet (getLastKnownLocation)
 *     en hoogstens één keer per kwartier een verse meting. Een tablet in een cabine hangt aan de
 *     stroom, maar een veldcomputer niet altijd, en de app hoort geen batterij te kosten.
 *   · Geen toestemming afdwingen. Is die er niet, dan stuurt de app niets mee en gebeurt er verder
 *     niets — geen venster, geen melding. Op een toestel waar wij device owner zijn, geeft
 *     Beheerder.kt de toestemming zelf; dan is er niets te vragen.
 *
 * Bewust géén Google Play-diensten: die zitten niet op elke machinetablet, en een bibliotheek die
 * ontbreekt zou de hele app laten vallen. LocationManager zit in Android zelf.
 */
object Toestelplek {
    /** Ouder dan dit: dan proberen we een verse meting. Een machine verplaatst niet elke minuut. */
    private const val VERS_MS = 15 * 60 * 1000L

    /** De laatste plek die we kennen, met het tijdstip van de méting (niet van het versturen). */
    data class Punt(val lat: Double, val lon: Double, val nauwkeurig: Float, val tijd: Long)

    @Volatile private var laatste: Punt? = null
    @Volatile private var bezig = false

    fun mag(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * De plek om mee te sturen. Nooit blokkerend: wat er is, gaat mee; is er niets of is het oud, dan
     * wordt er op de achtergrond om een verse gevraagd en gaat die de vólgende ronde mee.
     */
    fun huidige(ctx: Context): Punt? {
        if (!mag(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        beste(lm)?.let { if (laatste == null || it.tijd > laatste!!.tijd) laatste = it }
        val nu = System.currentTimeMillis()
        if (laatste == null || nu - laatste!!.tijd > VERS_MS) vraagVers(ctx, lm)
        return laatste
    }

    /** Wat de leveranciers al weten: de nieuwste van gps, netwerk en fused. */
    private fun beste(lm: LocationManager): Punt? {
        var beste: Location? = null
        for (naam in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
            val l = try { lm.getLastKnownLocation(naam) } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null }
            if (l != null && (beste == null || l.time > beste!!.time)) beste = l
        }
        val l = beste ?: return null
        // Een plek zonder tijd of op precies nul is geen plek maar een lege waarde.
        if (l.latitude == 0.0 && l.longitude == 0.0) return null
        return Punt(l.latitude, l.longitude, l.accuracy, if (l.time > 0) l.time else System.currentTimeMillis())
    }

    /**
     * Eén verse meting vragen, op de achtergrond.
     *
     * Er loopt er hoogstens één tegelijk: anders zou elke ronde er een bijzetten en staat de gps de
     * hele dag aan. De uitslag komt binnen wanneer ze binnenkomt en gaat de volgende ronde mee.
     */
    private fun vraagVers(ctx: Context, lm: LocationManager) {
        if (bezig) return
        val leverancier = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        bezig = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(leverancier, null, ctx.mainExecutor) { l ->
                    bezig = false
                    if (l != null && !(l.latitude == 0.0 && l.longitude == 0.0)) {
                        laatste = Punt(l.latitude, l.longitude, l.accuracy, if (l.time > 0) l.time else System.currentTimeMillis())
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(leverancier, { l ->
                    bezig = false
                    if (!(l.latitude == 0.0 && l.longitude == 0.0)) {
                        laatste = Punt(l.latitude, l.longitude, l.accuracy, if (l.time > 0) l.time else System.currentTimeMillis())
                    }
                }, ctx.mainLooper)
            }
        } catch (_: SecurityException) { bezig = false } catch (_: Exception) { bezig = false }
    }
}
