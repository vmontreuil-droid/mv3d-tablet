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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * De hele app, in één scherm.
 *
 * De koppelcode groot in het midden, en eronder een bolletje dat zegt of het werkt. Meer is er
 * niet, en meer hoeft er ook niet: wat er van kantoor komt, staat vanzelf in het programma dat
 * op dit toestel draait.
 *
 * Vóór het koppelen staat daar de eigen code van het toestel. Kantoor tikt die in bij "Toestel
 * toevoegen", en de app merkt het zelf: niemand hoeft in de cabine iets in te tikken. Wie toch
 * een code van kantoor kreeg, kan die nog altijd met de hand ingeven.
 *
 * De map zoekt de app zelf. De kiezer gaat vanzelf open op de map van het programma dat erop
 * draait — Unicontrol in een kraan, Trimble Access op een veldcomputer — en de gebruiker duwt één
 * keer op "Deze map gebruiken". Mag de app een map uit een eerdere installatie nog gebruiken, dan
 * slaan we die stap over en is de code werkelijk het enige.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vraagMeldingen()

        setContent {
            MaterialTheme(colorScheme = Mv3dColors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    val prefs = remember { Prefs(ctx) }
                    val scope = rememberCoroutineScope()

                    val code by prefs.codeFlow.collectAsState(initial = "")
                    val tree by prefs.treeFlow.collectAsState(initial = "")

                    // De mappenkiezer, al opengezet op de map die we verwachten. Blijvende toestemming
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
                            scope.launch { prefs.setTree(uri.toString()); startSync(); Batterij.vraag(ctx) }
                        }
                    }

                    // Wat er gebeurt zodra er een goede code is. Eén plek, want er zijn nu twee wegen
                    // naar hier — met de hand ingetikt, of door kantoor geclaimd — en die horen
                    // precies hetzelfde te doen.
                    suspend fun gebruikCode(goede: String) {
                        prefs.setCode(goede)
                        // De map erbij zoeken. Mag er al een — bij een herinstallatie blijft de
                        // toestemming soms staan — dan is de code werkelijk het enige geweest wat
                        // hij moest doen.
                        val alGegeven = Veldmap.alGegeven(ctx)
                        if (alGegeven != null) { prefs.setTree(alGegeven.toString()); startSync(); Batterij.vraag(ctx) }
                        else kiesMap.launch(Veldmap.kiezer())
                    }

                    // ── de eigen code, zolang er nog geen koppeling is ──
                    //
                    // Om de vijf seconden vragen: "welke code hoort bij mij, en heeft kantoor ze al?"
                    // Alleen terwijl het scherm open is. Een toestel dat in een la ligt, hoeft de
                    // server niet te blijven roepen; opent iemand de app, dan vraagt ze meteen.
                    var eigen by remember { mutableStateOf<Aanmelding?>(null) }
                    var geenVerbinding by remember { mutableStateOf(false) }
                    LaunchedEffect(code) {
                        if (code.isNotBlank()) return@LaunchedEffect
                        eigen = null; geenVerbinding = false
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            while (true) {
                                // Wat bewaard is, telt — niet wat het scherm denkt. Bij het openen
                                // staat de code hier even op leeg tot de voorkeuren gelezen zijn,
                                // en een gekoppeld toestel hoort zich dan niet opnieuw aan te melden.
                                if (prefs.code().isNotBlank()) return@repeatOnLifecycle
                                val server = prefs.server()
                                val installatie = prefs.installatie()
                                val a = withContext(Dispatchers.IO) { Api.aanmelden(ctx, server, installatie) }
                                if (a == null) {
                                    geenVerbinding = true
                                } else {
                                    geenVerbinding = false
                                    eigen = a
                                    if (a.gekoppeld) {
                                        // Eén keer, en helemaal. Het bewaren van de code herschikt dit
                                        // scherm en breekt deze lus af — zonder NonCancellable zou de
                                        // stap daarna (de map zoeken of de kiezer openen) halverwege
                                        // kunnen wegvallen. En de kiezer zelf legt de app even stil;
                                        // komt ze terug, dan staat de code al bewaard en begint er
                                        // niets opnieuw.
                                        withContext(NonCancellable) { if (prefs.code().isBlank()) gebruikCode(a.code) }
                                        return@repeatOnLifecycle
                                    }
                                }
                                delay(5_000)
                            }
                        }
                    }

                    Scherm(
                        code = code,
                        gekoppeld = code.isNotBlank() && tree.isNotBlank(),
                        eigenCode = eigen?.code,
                        geenVerbinding = geenVerbinding,
                        onKoppel = { ingetikt, klaar ->
                            scope.launch {
                                val server = prefs.server()
                                val goed = withContext(Dispatchers.IO) { Api(server, ingetikt).verifyCode() }
                                if (goed) gebruikCode(ingetikt)
                                klaar(goed)
                            }
                        },
                        onKiesMap = { kiesMap.launch(Veldmap.kiezer()) },
                        onBatterij = { Batterij.vraag(ctx) },
                        onBijwerken = { Updater.installeer(ctx, SyncService.updateKlaar) },
                        onOntkoppel = { scope.launch { prefs.wis(); stopSync() } },
                    )
                    LaunchedEffect(code, tree) { if (code.isNotBlank() && tree.isNotBlank()) startSync() }
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

    // Het zoeken naar een nieuwe versie stond hier, in onCreate.
    //
    // Dat is verhuisd naar SyncService. Deze app wordt één keer geopend om te koppelen en daarna
    // maandenlang niet meer aangeraakt — een controle die aan het openen hangt, loopt dus nooit.
    // De dienst kijkt één keer per dag en zet de versie stil klaar; hier staat alleen nog de knop
    // die de installer opent, en dan alleen als er werkelijk iets klaarstaat.

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
        // Welke versie hier draait, klein onder de naam.
        //
        // Zonder dit is er geen enkele manier om het te zien: er is geen menu, geen "over"-scherm,
        // en de instellingen van Android tonen alleen het nummer dat de Play Store kent — dat weet
        // van een zij-geladen app niets. Wie belt met "hij doet raar", weet nu ten minste met welke
        // versie hij belt. Het buildnummer erbij, want dáár praten we over.
        Text(
            "versie ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
            fontSize = 11.sp, color = TekstZacht, letterSpacing = 0.5.sp,
        )
    }
}

// ── Eén scherm ──────────────────────────────────────────────────────────────
//
// De code groot in het midden, en eronder of het gekoppeld is. Meer is er niet.
//
// Er stonden er twee — een koppelscherm en een statusscherm — en dat was er één te veel. Wie de
// app opent, wil één van twee dingen weten: welke code er in staat, of het werkt. Dat past op
// hetzelfde scherm, en dan hoeft niemand te leren welk scherm waarvoor dient.
//
// Vóór het koppelen staat hier de eigen code van het toestel, in hetzelfde vak als later de
// gekoppelde. Zo leest kantoor ze af — aan de telefoon of op een foto — en tikt ze in. Het veld
// om met de hand in te tikken zat vroeger op deze plek; het staat er nog, maar klein onderaan,
// want het is niet meer de gewone weg.
//
// Daarna staat de code er gewoon, groot genoeg om vanaf een meter af te lezen — want dat is
// waarvoor je hem later nog eens opzoekt.

@Composable
private fun Scherm (
    code: String,
    gekoppeld: Boolean,
    eigenCode: String?,
    geenVerbinding: Boolean,
    onKoppel: (String, (Boolean) -> Unit) -> Unit,
    onKiesMap: () -> Unit,
    onBatterij: () -> Unit,
    onBijwerken: () -> Unit,
    onOntkoppel: () -> Unit,
) {
    val ctx = LocalContext.current
    var getikt by remember { mutableStateOf("") }
    var bezig by remember { mutableStateOf(false) }
    var fout by remember { mutableStateOf(false) }
    // Het veld om met de hand in te tikken, dicht tot iemand erom vraagt. Na ontkoppelen weer dicht.
    var zelfIntikken by remember(code) { mutableStateOf(false) }

    // Elke seconde opnieuw kijken. Het bolletje hoort mee te bewegen met de werkelijkheid; een
    // groen bolletje dat groen blijft omdat niemand het bijwerkt, is erger dan geen bolletje.
    var tik by remember { mutableStateOf(System.currentTimeMillis()) }
    var magDoorlopen by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            tik = System.currentTimeMillis()
            magDoorlopen = Batterij.magDoorlopen(ctx)
            delay(1000)
        }
    }
    val laatste = SyncService.lastOk
    val leeft = gekoppeld && laatste > 0 && (tik - laatste) < 30_000

    // De code klopt al, maar de map is nog niet aangewezen: dan is er nog één tik te doen.
    val wachtOpMap = code.isNotBlank() && !gekoppeld

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(24.dp))
        Merk(bergHoogte = 72, tekstMaat = 26)
        Spacer(Modifier.height(40.dp))

        Text("KOPPELCODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = TekstZacht)
        Spacer(Modifier.height(10.dp))

        if (code.isNotBlank()) {
            CodeVak(code)
        } else {
            // ── de eigen code ──
            //
            // Nog geen antwoord: een klein wieltje, geen leeg vak — een vak zonder cijfers leest als
            // "hier hoort iets te staan en het is kapot". Geen internet: dat zeggen, in plaats van
            // een code te tonen die de server nog nooit gezien heeft.
            when {
                eigenCode != null -> CodeVak(eigenCode)
                geenVerbinding -> Text(
                    "Geen verbinding — de code verschijnt zodra er internet is.",
                    fontSize = 15.sp, textAlign = TextAlign.Center, color = TekstZacht,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp, color = Accent)
            }
        }

        Spacer(Modifier.height(28.dp))

        if (code.isNotBlank()) {
            // ── eronder: gekoppeld of niet ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (leeft) Accent else Color(0xFF5A6C82)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (leeft) "Gekoppeld" else if (wachtOpMap) "Nog een tik: wijs de map aan" else "Niet gekoppeld",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = if (leeft) Tekst else TekstZacht,
                )
            }
            SyncService.machineName?.takeIf { leeft }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 15.sp, color = TekstZacht)
            }
        } else if (eigenCode != null) {
            // ── eronder: we wachten op kantoor ──
            //
            // Een bolletje dat beweegt, want er gebeurt ook iets: om de vijf seconden vraagt de app
            // of de code al ingetikt is. Een stilstaand grijs bolletje zei "niet gekoppeld", en dat
            // klonk als een fout in plaats van als de stap waar we zitten.
            Row(verticalAlignment = Alignment.CenterVertically) {
                WachtBolletje()
                Spacer(Modifier.width(10.dp))
                Text("Wacht op koppeling…", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Tekst)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Tik deze code in op kantoor: MV3D Convertor → Toestel toevoegen.",
                fontSize = 14.sp, textAlign = TextAlign.Center, color = TekstZacht,
                modifier = Modifier.fillMaxWidth(),
            )
            // De code blijft staan als het internet even wegvalt — ze verandert niet — maar wie
            // wacht, hoort te weten waarom er niets gebeurt.
            if (geenVerbinding) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Even geen verbinding met mv3d.be — we blijven het proberen.",
                    fontSize = 13.sp, textAlign = TextAlign.Center, color = TekstZacht,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Wat er hapert, in één zin.
        //
        // Dit stond er niet, en dat was precies het probleem: een bestand dat niet weggeschreven
        // raakte, was op dit scherm niet te onderscheiden van een tablet die niet gekoppeld was.
        // De machinist ziet nu wát er scheelt; de kaart hierboven blijft groen, want met de server
        // is er niets aan de hand.
        SyncService.lastFout?.takeIf { code.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                fontSize = 14.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── legt de batterijbesparing de app stil? ──
        //
        // Alleen te zien wanneer het werkelijk knelt, en dan wel duidelijk. Een tablet die uren in
        // een stilstaande cabine ligt, is precies waar Android denkt: die app heeft niemand nodig.
        // Maar juist dan hoort de werf die je van kantoor stuurt binnen te komen — en als ze dat
        // niet doet, is er niets te zien wat verklaart waarom.
        //
        // Zodra de uitzondering er is, verdwijnt deze regel. Een waarschuwing die blijft staan als
        // ze verholpen is, leert je om waarschuwingen niet meer te lezen.
        if (code.isNotBlank() && !magDoorlopen) {
            Spacer(Modifier.height(22.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Kaart).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Android mag deze app stilleggen",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Tekst, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Dan komt een werf 's nachts niet binnen. Eén tik en dat is opgelost.",
                    fontSize = 13.5.sp, color = TekstZacht, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = onBatterij, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Laten doorlopen", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── er staat een nieuwe versie klaar ──
        //
        // Ze is al binnengehaald door de dienst; hier is alleen de tik over die Android hoe dan
        // ook wil. Geen venster dat vanzelf opengaat: wie aan het graven is, wordt niet
        // onderbroken door zijn eigen gereedschap.
        if (SyncService.updateKlaar > 0) {
            Spacer(Modifier.height(22.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Kaart).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nieuwe versie klaar", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Tekst)
                SyncService.updateNaam.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 13.sp, color = TekstZacht)
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = onBijwerken, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Bijwerken", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (wachtOpMap) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onKiesMap, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Map aanwijzen", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "De kiezer staat al op de map die we verwachten. Duw op Deze map gebruiken.",
                fontSize = 13.sp, textAlign = TextAlign.Center, color = TekstZacht,
            )
        }

        Spacer(Modifier.height(44.dp))

        // Klein en onderaan. Ze horen er te zijn — een tablet verhuist, een map verandert — maar
        // ze zijn niet waarvoor je dit scherm opent.
        if (code.isNotBlank()) {
            TextButton(onClick = onKiesMap) { Text("Andere map kiezen", fontSize = 13.sp, color = TekstZacht) }
            TextButton(onClick = onOntkoppel) { Text("Ontkoppelen", fontSize = 13.sp, color = TekstZacht) }
        } else if (!zelfIntikken) {
            // De oude weg, voor wie van kantoor al een code kreeg. Ze blijft bestaan: een machine
            // die eerst in het portaal aangemaakt werd, heeft haar code al.
            TextButton(onClick = { zelfIntikken = true }) {
                Text("Code van kantoor gekregen? Tik hem hier in", fontSize = 13.sp, color = TekstZacht)
            }
        } else {
            OutlinedTextField(
                value = getikt,
                onValueChange = { nieuw ->
                    // Alleen cijfers, en niet meer dan acht. Wie plakt, plakt soms een spatie of
                    // een streepje mee; dat hoort de app zelf weg te halen in plaats van erover te
                    // klagen.
                    getikt = nieuw.filter { it.isDigit() }.take(8)
                    fout = false
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = fout,
                placeholder = { Text("00000000", fontSize = 36.sp, color = TekstZacht, textAlign = TextAlign.Center) },
                textStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = 8.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { bezig = true; onKoppel(getikt) { goed -> bezig = false; fout = !goed } },
                enabled = !bezig && getikt.length == 8,
                modifier = Modifier.fillMaxWidth().height(62.dp),
            ) {
                if (bezig) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = OpAccent)
                else Text("Koppelen", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            if (fout) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Die code kennen we niet. Kijk hem na in de MV3D Convertor of op mv3d.be, bij Machines.",
                    fontSize = 14.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Een code van acht cijfers in twee groepjes van vier, in het groen.
 *
 * Hetzelfde vak vóór en na het koppelen. Wat kantoor van het scherm afleest, hoort er later
 * precies zo uit te zien als wat er dan in het portaal staat.
 */
@Composable
private fun CodeVak (code: String) {
    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Kaart).padding(horizontal = 30.dp, vertical = 18.dp)) {
        Text(
            code.chunked(4).joinToString("  "),
            fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = Accent,
        )
    }
}

/** Het groene bolletje dat zachtjes aan- en uitgaat terwijl de app op kantoor wacht. */
@Composable
private fun WachtBolletje () {
    val puls = rememberInfiniteTransition(label = "wacht")
    val helder by puls.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "helder",
    )
    val maat by puls.animateFloat(
        initialValue = 0.8f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "maat",
    )
    Box(
        Modifier.size(14.dp)
            .graphicsLayer { alpha = helder; scaleX = maat; scaleY = maat }
            .clip(CircleShape).background(Accent),
    )
}
