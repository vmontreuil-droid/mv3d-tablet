package be.mv3d.tablet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── MV3D-huisstijl: goud accent op donkere grond ──
private val Gold = Color(0xFFC8A862)
private val Mv3dColors = darkColorScheme(
    primary = Gold, onPrimary = Color(0xFF14100A),
    primaryContainer = Color(0xFF2A2417), onPrimaryContainer = Gold,
    secondary = Gold, onSecondary = Color(0xFF14100A),
    background = Color(0xFF0B1017), onBackground = Color(0xFFE8EEF5),
    surface = Color(0xFF161F2B), onSurface = Color(0xFFE8EEF5),
    surfaceVariant = Color(0xFF1E2A38), onSurfaceVariant = Color(0xFFA9B7C7),
    outline = Color(0xFF33465A),
    error = Color(0xFFF08A8A), onError = Color(0xFF14100A),
)

/**
 * De hele app, in twee schermen.
 *
 * ── waarom er zo weinig staat ──
 *
 * Hier zaten er zes: een aanmeldscherm met een mailadres, een overzicht van alle kranen, het
 * portaal in een venster, een omzetter, een installatiewizard van vier stappen, en een
 * instellingenblad. Achttien bestanden Kotlin. Op een tablet in een cabine is dat allemaal iets
 * dat stuk kan gaan terwijl niemand kan meekijken.
 *
 * Wat overblijft:
 *
 *   · koppelen — acht cijfers intikken en de Unicontrol-map aanwijzen. Eén keer.
 *   · kijken of het loopt — één regel die zegt wanneer er voor het laatst contact was.
 *
 * De machinist hoeft hier nooit meer te zijn. Wat er van het portaal komt, staat vanzelf in
 * Unicontrol; deze app heeft geen knop die hij moet duwen.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vraagMeldingen()
        zoekBijwerking()

        setContent {
            MaterialTheme(colorScheme = Mv3dColors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    val prefs = remember { Prefs(ctx) }
                    val scope = rememberCoroutineScope()

                    val code by prefs.codeFlow.collectAsState(initial = "")
                    val tree by prefs.treeFlow.collectAsState(initial = "")
                    val gekoppeld = code.isNotBlank() && tree.isNotBlank()

                    // De map kiezen gaat via de mappenkiezer van Android (SAF). We vragen om
                    // blijvende toestemming: anders is ze na een herstart weg en staat er 's
                    // morgens niets klaar, zonder dat iemand weet waarom.
                    val kiesMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) {
                            try {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            } catch (_: Exception) { }
                            scope.launch { prefs.setTree(uri.toString()); startSync() }
                        }
                    }

                    if (gekoppeld) {
                        StatusScherm(
                            code = code,
                            mapNaam = mapNaam(tree),
                            onOntkoppel = { scope.launch { prefs.wis(); stopSync() } },
                            onKiesMap = { kiesMap.launch(null) },
                        )
                        LaunchedEffect(Unit) { startSync() }
                    } else {
                        KoppelScherm(
                            heeftMap = tree.isNotBlank(),
                            mapNaam = mapNaam(tree),
                            onKiesMap = { kiesMap.launch(null) },
                            onKoppel = { ingetikt, klaar ->
                                scope.launch {
                                    val server = prefs.server()
                                    val goed = withContext(Dispatchers.IO) { Api(server, ingetikt).verifyCode() }
                                    if (goed) { prefs.setCode(ingetikt); startSync() }
                                    klaar(goed)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** Zonder deze toestemming mag een voorgronddienst op Android 13+ geen melding tonen. */
    private fun vraagMeldingen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        try { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) } catch (_: Exception) { }
    }

    /**
     * Is er een nieuwere bouw?
     *
     * De app werkt zichzelf bij vanaf de publieke releases. Android laat een zij-geladen app niet
     * stil herinstalleren, dus er blijft één tik "Installeren" over — dat is een grens van Android,
     * niet van ons. Lukt het niet (geen netwerk op de werf), dan gebeurt er niets: bijwerken mag
     * nooit in de weg staan van het werk.
     */
    private fun zoekBijwerking() {
        Thread {
            try {
                val u = Updater.check() ?: return@Thread
                Updater.downloadAndInstall(this, u.apkUrl)
            } catch (_: Exception) { }
        }.start()
    }

    private fun startSync() {
        val i = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, i) else startService(i)
    }

    private fun stopSync() { stopService(Intent(this, SyncService::class.java)) }

    private fun mapNaam(tree: String): String =
        if (tree.isBlank()) "" else (Uri.parse(tree).lastPathSegment?.substringAfterLast(':') ?: tree)
}

// ── het koppelscherm ────────────────────────────────────────────────────────

@Composable
private fun KoppelScherm (
    heeftMap: Boolean,
    mapNaam: String,
    onKiesMap: () -> Unit,
    onKoppel: (String, (Boolean) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var bezig by remember { mutableStateOf(false) }
    var fout by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("MV3D", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Gold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Deze tablet koppelen aan een machine",
            fontSize = 17.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        // ── stap 1: de map ──
        Stap(1, "Wijs de Unicontrol-map aan", heeftMap) {
            Text(
                if (heeftMap) mapNaam else "De map waar Unicontrol zijn projecten bewaart.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onKiesMap, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (heeftMap) "Andere map kiezen" else "Map kiezen", fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── stap 2: de code ──
        Stap(2, "Tik de code van acht cijfers in", false) {
            Text(
                "Die staat op mv3d.be, bij Machines.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { nieuw ->
                    // Alleen cijfers, en niet meer dan acht. Wie plakt, plakt soms een spatie of
                    // een streepje mee; dat hoort de app zelf weg te halen in plaats van erover
                    // te klagen.
                    code = nieuw.filter { it.isDigit() }.take(8)
                    fout = false
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = fout,
                placeholder = { Text("00000000", fontSize = 24.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth(),
            )
            if (fout) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Die code kennen we niet. Kijk hem na op mv3d.be, bij Machines.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { bezig = true; onKoppel(code) { goed -> bezig = false; fout = !goed } },
            enabled = !bezig && code.length == 8 && heeftMap,
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) {
            if (bezig) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Koppelen", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Daarna hoef je hier niets meer te doen. Wat er van kantoor komt, staat vanzelf in Unicontrol.",
            fontSize = 13.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ── het statusscherm ────────────────────────────────────────────────────────

@Composable
private fun StatusScherm (
    code: String,
    mapNaam: String,
    onOntkoppel: () -> Unit,
    onKiesMap: () -> Unit,
) {
    // Elke seconde opnieuw kijken. Dit scherm staat open terwijl iemand wacht tot zijn werf
    // binnenkomt; dan hoort de regel eronder mee te bewegen.
    var tik by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { tik = System.currentTimeMillis(); delay(1000) } }

    val ok = SyncService.lastOk > 0 && (tik - SyncService.lastOk) < 30_000
    val naam = SyncService.machineName

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        Box(
            Modifier.size(96.dp).clip(CircleShape)
                .background(if (ok) Color(0xFF1E3A24) else Color(0xFF2A2417)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (ok) "✓" else "…", fontSize = 44.sp, color = if (ok) Color(0xFF7BD88F) else Gold)
        }

        Spacer(Modifier.height(20.dp))
        Text(naam ?: "Gekoppeld", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (ok) "De tablet luistert." else "Nog geen contact met MV3D.",
            fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(18.dp)) {
                Regel("Laatste contact", geleden(SyncService.lastOk, tik))
                Spacer(Modifier.height(10.dp))
                Regel("Map", mapNaam)
                Spacer(Modifier.height(10.dp))
                Regel("Code", code.chunked(4).joinToString(" "))
                Spacer(Modifier.height(10.dp))
                Regel("Status", SyncService.lastStatus)
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onKiesMap, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Andere map kiezen", fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onOntkoppel, modifier = Modifier.fillMaxWidth()) {
            Text("Ontkoppelen", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Regel (naam: String, waarde: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(naam, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(waarde, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@Composable
private fun Stap (nummer: Int, titel: String, gedaan: Boolean, inhoud: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (gedaan) Color(0xFF1E3A24) else MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (gedaan) "✓" else "$nummer", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (gedaan) Color(0xFF7BD88F) else Gold)
                }
                Spacer(Modifier.width(10.dp))
                Text(titel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            inhoud()
        }
    }
}

/** "12 seconden geleden" — een tijdstip zou je zelf moeten aftrekken. */
private fun geleden (wanneer: Long, nu: Long): String {
    if (wanneer <= 0) return "nog nooit"
    val sec = ((nu - wanneer) / 1000).coerceAtLeast(0)
    return when {
        sec < 60 -> "$sec seconden geleden"
        sec < 3600 -> "${sec / 60} minuten geleden"
        sec < 86400 -> "${sec / 3600} uur geleden"
        else -> "${sec / 86400} dagen geleden"
    }
}
