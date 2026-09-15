package be.mv3d.tablet

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * De app als eigenaar van het toestel.
 *
 * ── waarom dit er is ──
 *
 * Gevraagd: het bijwerken moet volledig vanzelf gaan. Dat kan niet met een gewone app. Android
 * laat een zij-geladen app niet stil herinstalleren — er komt altijd een scherm "Wil je deze app
 * installeren?", en iemand moet erop duwen. In een cabine, met een machinist die aan het graven is,
 * gebeurt dat niet, en dan loopt de vloot maanden achter.
 *
 * Er is één uitzondering en die is met opzet gemaakt: een app die *device owner* is. Dat is de
 * stand die Android voor bedrijfstoestellen kent — een tablet die van de zaak is en centraal
 * beheerd wordt, en dat is precies wat een machinetablet is. Zo'n app mag zichzelf stil bijwerken.
 *
 * ── wat het kost, en dat is niet niets ──
 *
 * Eigenaar worden kan alleen op een toestel zonder accounts: fabrieksnieuw, of net teruggezet. Eén
 * keer per tablet, via USB:
 *
 *     adb shell dpm set-device-owner be.mv3d.tablet/.Beheerder
 *
 * Daarna hoeft er nooit meer iets. Lukt het niet — een toestel dat al in gebruik is, een Google-
 * account dat erop staat — dan blijft alles werken zoals het werkte: de melding in de balk, en de
 * machinist tikt één keer. Er gaat dus niets stuk als dit niet gezet is; het wordt alleen niet
 * automatisch.
 *
 * ── wat het NIET doet ──
 *
 * Deze stand geeft veel macht: een device owner kan een toestel wissen, schermvergrendeling
 * afdwingen, apps blokkeren. Wij gebruiken er twee dingen van en verder niets: stil bijwerken, en
 * onszelf het recht geven om bij de werfmappen te komen. Er staat hier geen beleid dat iets
 * afdwingt of iets verbiedt, en dat hoort zo te blijven — het is de tablet van de klant.
 */
class Beheerder : DeviceAdminReceiver() {

    companion object {
        /** Onszelf, zoals dpm en de PackageInstaller ons kennen. */
        fun wie(ctx: Context) = ComponentName(ctx, Beheerder::class.java)

        /**
         * Zijn wij de eigenaar van dit toestel?
         *
         * Zo niet, dan is dat geen fout: dan werkt het bijwerken zoals het altijd werkte, met een
         * melding en één tik. Deze vraag mag dus nergens iets tegenhouden.
         */
        fun isEigenaar(ctx: Context): Boolean = try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName)
        } catch (_: Exception) { false }
    }
}
