package be.mv3d.tablet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── De kleuren van het platform ─────────────────────────────────────────────
//
// Dezelfde als op mv3d.be: groen accent op marineblauw. Ze stonden hier nog op het goud van de
// vorige huisstijl, en een app die er anders uitziet dan het portaal waar ze bij hoort, voelt als
// een ander product.
//
// Elk vlak staat er expliciet in. Material3 haalt de kleur van een Card uit
// surfaceContainerHighest, en die had ik niet gezet — dan valt hij terug op het lichte
// standaardpalet, en dat gaf een lichtroze kaart op een donkere bladzijde.
private val Accent = Color(0xFF90CC1E)        // --accent
private val OpAccent = Color(0xFF14243F)      // --hud-op-accent
private val Grond = Color(0xFF081426)         // --site-bg
private val Kaart = Color(0xFF0F2B50)         // --bg-card
private val Kaart2 = Color(0xFF143458)        // --bg-card-2
private val Rand = Color(0xFF274A78)          // --border-soft
private val Tekst = Color(0xFFE8EEF5)
private val TekstZacht = Color(0xFF9FB0C3)    // --text-soft

private val Mv3dColors = darkColorScheme(
    primary = Accent, onPrimary = OpAccent,
    primaryContainer = Kaart2, onPrimaryContainer = Accent,
    secondary = Accent, onSecondary = OpAccent,
    background = Grond, onBackground = Tekst,
    surface = Kaart, onSurface = Tekst,
    surfaceVariant = Kaart2, onSurfaceVariant = TekstZacht,
    surfaceContainer = Kaart, surfaceContainerHigh = Kaart2, surfaceContainerHighest = Kaart2,
    surfaceContainerLow = Kaart, surfaceContainerLowest = Grond,
    outline = Rand, outlineVariant = Rand,
    error = Color(0xFFF07360), onError = OpAccent,
)

/**
 * De hele app, in twee schermen.
 *
 * ── koppelen ──
 *
 * Alleen de code. De map zoekt de app zelf: de kiezer gaat vanzelf open op de map van Unicontrol,
 * en de machinist duwt één keer op "Deze map gebruiken". Mag de app een map uit een eerdere
 * installatie nog gebruiken, dan slaan we die stap over en is de code werkelijk het enige.
 *
 * ── daarna ──
 *
 * Een bolletje, het woord "gekoppeld", en zijn code. Meer heeft hij hier niet te zoeken: wat er
 * van kantoor komt, staat vanzelf in Unicontrol.
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

                    // De mappenkiezer, al opengezet op de map van Unicontrol. Blijvende toestemming
                    // vragen is geen luxe: zonder dat is ze na een herstart weg en staat er 's
                    // morgens niets klaar, zonder dat iemand weet waarom.
                    val kiesMap = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult(),
                    ) { res ->
                        val uri = res.data?.data
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

                    if (code.isNotBlank() && tree.isNotBlank()) {
                        StatusScherm(
                            code = code,
                            onOntkoppel = { scope.launch { prefs.wis(); stopSync() } },
                            onKiesMap = { kiesMap.launch(Unicontrol.kiezer()) },
                        )
                        LaunchedEffect(Unit) { startSync() }
                    } else {
                        KoppelScherm(
                            wachtOpMap = code.isNotBlank(),
                            onKiesMap = { kiesMap.launch(Unicontrol.kiezer()) },
                            onKoppel = { ingetikt, klaar ->
                                scope.launch {
                                    val server = prefs.server()
                                    val goed = withContext(Dispatchers.IO) { Api(server, ingetikt).verifyCode() }
                                    if (goed) {
                                        prefs.setCode(ingetikt)
                                        // De map erbij zoeken. Mag er al een — bij een
                                        // herinstallatie blijft de toestemming soms staan — dan is
                                        // de code het enige geweest wat hij moest doen.
                                        val alGegeven = Unicontrol.alGegeven(ctx)
                                        if (alGegeven != null) { prefs.setTree(alGegeven.toString()); startSync() }
                                        else kiesMap.launch(Unicontrol.kiezer())
                                    }
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
     * Android laat een zij-geladen app niet stil herinstalleren, dus er blijft één tik
     * "Installeren" over — een grens van Android, niet van ons. Lukt het niet (geen netwerk op de
     * werf), dan gebeurt er niets: bijwerken mag nooit in de weg staan van het werk.
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
}


/**
 * Het merk: de berg met MV3D eronder.
 *
 * Dezelfde als op de site en in de Convertor. Een app die zijn eigen logo verzint, hoort niet bij
 * het product waar hij bij hoort — en dit scherm is het eerste wat een machinist van ons ziet.
 */
@Composable
private fun Merk (bergHoogte: Int = 72, tekstMaat: Int = 26) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.mv3d_berg),
            contentDescription = "MV3D",
            modifier = Modifier.height(bergHoogte.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text("MV3D", fontSize = tekstMaat.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = Tekst)
    }
}

// ── koppelen: alleen de code ────────────────────────────────────────────────

@Composable
private fun KoppelScherm (
    wachtOpMap: Boolean,
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
        Spacer(Modifier.height(56.dp))
        Merk(bergHoogte = 84, tekstMaat = 30)
        Spacer(Modifier.height(10.dp))

        if (wachtOpMap) {
            // De code klopte, maar de kiezer is weggeklikt. Dan is er nog één ding te doen, en
            // dat hoort er te staan in plaats van een leeg codeveld dat hij opnieuw invult.
            Text("Nog één tik", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Tekst)
            Spacer(Modifier.height(4.dp))
            Text(
                "Wijs de map van Unicontrol aan. De kiezer staat er al open; duw op “Deze map gebruiken”.",
                fontSize = 14.sp, textAlign = TextAlign.Center, color = TekstZacht,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onKiesMap, modifier = Modifier.fillMaxWidth().height(62.dp)) {
                Text("Map aanwijzen", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            return@Column
        }

        Text("Tik de koppelcode in", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Tekst)
        Spacer(Modifier.height(4.dp))
        Text(
            "Acht cijfers. Ze staan op mv3d.be, bij Machines.",
            fontSize = 14.sp, textAlign = TextAlign.Center, color = TekstZacht,
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { nieuw ->
                // Alleen cijfers, en niet meer dan acht. Wie plakt, plakt soms een spatie of een
                // streepje mee; dat hoort de app zelf weg te halen in plaats van erover te klagen.
                code = nieuw.filter { it.isDigit() }.take(8)
                fout = false
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = fout,
            placeholder = { Text("00000000", fontSize = 30.sp, color = TekstZacht, textAlign = TextAlign.Center) },
            textStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = 6.sp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (fout) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Die code kennen we niet. Kijk hem na op mv3d.be, bij Machines.",
                fontSize = 14.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { bezig = true; onKoppel(code) { goed -> bezig = false; fout = !goed } },
            enabled = !bezig && code.length == 8,
            modifier = Modifier.fillMaxWidth().height(62.dp),
        ) {
            if (bezig) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = OpAccent)
            else Text("Koppelen", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "Daarna wijst u één keer de map van Unicontrol aan, en verder hoeft u hier niets meer te doen.",
            fontSize = 13.sp, textAlign = TextAlign.Center, color = TekstZacht,
        )
    }
}

// ── daarna: een bolletje, gekoppeld, en de code ─────────────────────────────

@Composable
private fun StatusScherm (
    code: String,
    onOntkoppel: () -> Unit,
    onKiesMap: () -> Unit,
) {
    // Elke seconde opnieuw kijken. Het bolletje hoort mee te bewegen met de werkelijkheid; een
    // groen bolletje dat groen blijft omdat niemand het bijwerkt, is erger dan geen bolletje.
    var tik by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { tik = System.currentTimeMillis(); delay(1000) } }

    val laatste = SyncService.lastOk
    val leeft = laatste > 0 && (tik - laatste) < 30_000
    val naam = SyncService.machineName

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Merk(bergHoogte = 64, tekstMaat = 22)

        Spacer(Modifier.height(28.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .background(if (leeft) Accent else Color(0xFF5A6C82)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (leeft) "Gekoppeld" else "Geen verbinding",
                fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Tekst,
            )
        }
        if (naam != null) {
            Spacer(Modifier.height(6.dp))
            Text(naam, fontSize = 17.sp, color = TekstZacht)
        }

        Spacer(Modifier.height(36.dp))

        Text("KOPPELCODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = TekstZacht)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Kaart).padding(horizontal = 28.dp, vertical = 16.dp)) {
            Text(
                code.chunked(4).joinToString("  "),
                fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp, color = Accent,
            )
        }

        Spacer(Modifier.height(56.dp))

        // Klein en onderaan. Ze horen er te zijn — een tablet verhuist, een map verandert — maar
        // ze zijn niet waarvoor je dit scherm opent.
        TextButton(onClick = onKiesMap) { Text("Andere map kiezen", fontSize = 13.sp, color = TekstZacht) }
        TextButton(onClick = onOntkoppel) { Text("Ontkoppelen", fontSize = 13.sp, color = TekstZacht) }
    }
}
