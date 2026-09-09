package be.mv3d.tablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Start de sync automatisch — na een herstart van de tablet, én na een bijwerking van de app zelf.
 *
 * Dat tweede ontbrak, en dat is precies het geval dat het vaakst voorkomt. Android stopt een app
 * die vervangen wordt: de dienst valt stil en start pas weer als iemand de app opent. Wie in de
 * cabine op "bijwerken" tikte, zag daarna in het portaal dat zijn kraan niet meer gekoppeld was —
 * terwijl er niets mis was met de koppeling. De tablet zweeg gewoon.
 *
 * MY_PACKAGE_REPLACED komt alleen bij de app die zélf vervangen is, en die mag op dat moment een
 * dienst op de voorgrond starten. Precies waar hij voor bedoeld is.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val start = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!start) return
        val i = Intent(ctx, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
    }
}
