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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── MV3D-huisstijl: goud accent op donkere grond ──
private val Gold = Color(0xFFC8A862)
private val GoldDark = Color(0xFFA88A4A)
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = Mv3dColors) {
                val scope = rememberCoroutineScope()
                val code by prefs.codeFlow.collectAsState(initial = "")
                val tree by prefs.treeFlow.collectAsState(initial = "")

                var screen by remember { mutableStateOf("home") } // "home" | "pair"
                var codeField by remember(code) { mutableStateOf(code) }
                var running by remember { mutableStateOf(SyncService.running) }
                var status by remember { mutableStateOf(SyncService.lastStatus) }
                var machineName by remember { mutableStateOf(SyncService.machineName) }
                var syncStarting by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { while (true) { status = SyncService.lastStatus; running = SyncService.running; machineName = SyncService.machineName; if (running) syncStarting = false; kotlinx.coroutines.delay(700) } }

                // auto-update
                var update by remember { mutableStateOf<Updater.Update?>(null) }
                var updBusy by remember { mutableStateOf(false) }
                var updErr by remember { mutableStateOf<String?>(null) }
                var updDismissed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { while (true) { runCatching { withContext(Dispatchers.IO) { Updater.check() } }.getOrNull()?.let { update = it }; kotlinx.coroutines.delay(60_000) } }
                LaunchedEffect(update?.versionCode) { if (update != null) updDismissed = false }
                val doUpdate: () -> Unit = {
                    val u = update
                    if (u != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                            updErr = "Zet 'installeren van onbekende apps' AAN voor MV3D, en tik dan opnieuw op Bijwerken."
                            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        } else {
                            updBusy = true; updErr = null
                            scope.launch { val e = withContext(Dispatchers.IO) { runCatching { Updater.downloadAndInstall(this@MainActivity, u.apkUrl) }.exceptionOrNull() }; updBusy = false; if (e != null) updErr = "Update mislukt: ${e.message}" }
                        }
                    }
                }

                val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null) {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        scope.launch { prefs.setTree(uri.toString()) }
                    }
                }
                val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
                    result.contents?.let { raw -> val c = extractCode(raw); codeField = c; scope.launch { prefs.setCode(c) } }
                }
                LaunchedEffect(code, tree) {
                    if (code.isNotBlank() && tree.isNotBlank() && !SyncService.running) { requestRuntimePerms(askPerms); startSvc(SyncService::class.java) }
                }

                // ── Bestandsconvertor (Unicontrol) ──
                val uni by prefs.uniFlow.collectAsState(initial = "")
                var convBrand by remember { mutableStateOf("") }
                var convSources by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }
                var convWerf by remember { mutableStateOf("") }
                var convBusy by remember { mutableStateOf(false) }
                var convMsg by remember { mutableStateOf<String?>(null) }
                var convDone by remember { mutableStateOf(false) }
                val pickSources = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    val list = uris.mapNotNull { u ->
                        val n = DocumentFile.fromSingleUri(this@MainActivity, u)?.name ?: u.lastPathSegment ?: "bestand"
                        n to u
                    }
                    if (list.isNotEmpty()) convSources = list
                }
                val pickUni = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null) {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        scope.launch { prefs.setUni(uri.toString()) }
                    }
                }
                val doConvert = {
                    if (convSources.isNotEmpty() && convWerf.isNotBlank() && uni.isNotBlank()) {
                        convBusy = true; convMsg = null; convDone = false
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                runCatching {
                                    val srcBytes = convSources.map { (n, u) -> n to (contentResolver.openInputStream(u)?.use { s -> s.readBytes() } ?: ByteArray(0)) }
                                    val out = Api(prefs.server(), prefs.code()).convertUnicontrol(convWerf.trim(), srcBytes)
                                    writeUnicontrolProject(Uri.parse(uni), convWerf.trim(), out.files)
                                    out
                                }
                            }
                            convBusy = false
                            res.onSuccess { convDone = true; convMsg = "Klaar: ${it.surfaces} oppervlak, ${it.lines} lijnen → ${it.folder}. Sluit Unicontrol volledig af en start het opnieuw." }
                                .onFailure { convMsg = "Fout: ${it.message}" }
                        }
                    }
                }

                val openPortal = { startActivity(Intent(this@MainActivity, WebViewActivity::class.java)) }

                // ── Login (MV3D-account) ──
                val authToken by prefs.authFlow.collectAsState(initial = "")
                val authEmail by prefs.authEmailFlow.collectAsState(initial = "")
                val storedLang by prefs.langFlow.collectAsState(initial = "")
                val lang = storedLang.ifBlank { java.util.Locale.getDefault().language.let { if (it in listOf("nl", "fr", "en")) it else "nl" } }
                var loginBusy by remember { mutableStateOf(false) }
                var loginError by remember { mutableStateOf<String?>(null) }
                val doEmailLogin: (String, String) -> Unit = { em, p ->
                    loginBusy = true; loginError = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { runCatching { Api(prefs.server(), "").login(em, p) }.getOrNull() }
                        loginBusy = false
                        if (res != null) prefs.setAuth(res.first, res.second) else loginError = loginErrorText(lang)
                    }
                }
                val doCodeLogin: (String) -> Unit = { c ->
                    loginBusy = true; loginError = null
                    scope.launch {
                        val cc = c.trim().uppercase()
                        val ok = cc.isNotBlank() && withContext(Dispatchers.IO) { runCatching { Api(prefs.server(), cc).verifyCode() }.getOrDefault(false) }
                        loginBusy = false
                        if (ok) { prefs.setCode(cc); prefs.setAuth("crane:$cc", cc) } else loginError = loginErrorText(lang)
                    }
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (authToken.isBlank()) {
                        LoginScreen(lang = lang, onLang = { scope.launch { prefs.setLang(it) } }, busy = loginBusy, error = loginError, onEmailLogin = doEmailLogin, onCodeLogin = doCodeLogin, onSkip = { scope.launch { prefs.setAuth("guest", "") } })
                    } else when (screen) {
                        "home" -> HomeScreen(
                            version = "build ${BuildConfig.VERSION_CODE}",
                            coupled = code.isNotBlank() && tree.isNotBlank(),
                            code = code,
                            machineName = machineName,
                            onPair = { screen = "pair" },
                            onConvert = { b -> convBrand = b; convSources = emptyList(); convWerf = ""; convMsg = null; convDone = false; screen = "convert" },
                        )
                        "convert" -> ConverterScreen(
                            brand = convBrand,
                            sources = convSources,
                            werf = convWerf,
                            uniChosen = uni.isNotBlank(),
                            busy = convBusy, done = convDone, message = convMsg,
                            onBack = { screen = "home" },
                            onPickSources = { pickSources.launch(arrayOf("*/*")) },
                            onPickUni = { pickUni.launch(null) },
                            onWerf = { convWerf = it },
                            onConvert = { doConvert() },
                            onLaunchUni = { launchUnicontrol() },
                        )
                        else -> PairingScreen(
                            code = code, folderLabel = folderLabel(tree),
                            coupled = code.isNotBlank() && tree.isNotBlank(),
                            running = running, status = status,
                            syncBusy = syncStarting, version = "build ${BuildConfig.VERSION_CODE}",
                            onBack = { screen = "home" },
                            onCode = { codeField = it; scope.launch { prefs.setCode(it) } },
                            onScan = { scan.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false).setPrompt("Scan de koppelcode-QR")) },
                            onOpenPortal = openPortal,
                            onPickFolder = { pickTree.launch(null) },
                            onStartSync = { syncStarting = true; requestRuntimePerms(askPerms); startSvc(SyncService::class.java) },
                            onStopSync = { syncStarting = false; stopService(Intent(this@MainActivity, SyncService::class.java)) },
                            codeField = codeField,
                            authEmail = authEmail,
                            onLogout = { scope.launch { prefs.clearAuth() } },
                        )
                    }
                }

                // update-pop-up (over beide schermen)
                val up = update
                if (up != null && !updDismissed) {
                    AlertDialog(
                        onDismissRequest = { updDismissed = true },
                        title = { Text("Nieuwe versie beschikbaar") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Er staat een update klaar (${up.versionName}). Nu bijwerken?"); if (updErr != null) Text(updErr!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } },
                        confirmButton = { Button(onClick = doUpdate, enabled = !updBusy) { if (updBusy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(8.dp)) }; Text(if (updBusy) "Downloaden…" else "Bijwerken") } },
                        dismissButton = { TextButton(onClick = { updDismissed = true }) { Text("Later") } },
                    )
                }
            }
        }
    }

    private fun startSvc(cls: Class<*>) {
        val i = Intent(this, cls)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    /** Schrijft het project in <Unicontrol>/Projects/<werf>/ en de .wkt in
     *  <Unicontrol>/CoordinateSystems/ (+ /cloud/), ongeacht welke map gekozen is. */
    private fun writeUnicontrolProject(uniTreeUri: Uri, werf: String, files: List<ConvOut>) {
        val root = DocumentFile.fromTreeUri(this, uniTreeUri) ?: throw RuntimeException("Unicontrol-map ongeldig")
        fun childDir(dir: DocumentFile, name: String): DocumentFile =
            dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name) ?: throw RuntimeException("Kan map '$name' niet maken")
        fun writeInto(dir: DocumentFile, fname: String, text: String) {
            dir.findFile(fname)?.delete()
            val doc = dir.createFile("application/octet-stream", fname) ?: throw RuntimeException("Kan $fname niet aanmaken")
            contentResolver.openOutputStream(doc.uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        // Unicontrol-hoofdmap bepalen (bevat Projects én CoordinateSystems)
        val uniRoot: DocumentFile = when {
            root.name.equals("Unicontrol", true) -> root
            root.findFile("Unicontrol")?.isDirectory == true -> root.findFile("Unicontrol")!!
            else -> root   // ze kozen Unicontrol zelf, Projects, of de opslag-root
        }
        val projects = if (root.name.equals("Projects", true)) root else childDir(uniRoot, "Projects")
        val werfDir = childDir(projects, werf)

        var coordSys: DocumentFile? = null
        var coordCloud: DocumentFile? = null
        for (f in files) {
            if (f.dir == "coordsys") {
                if (coordSys == null) { coordSys = childDir(uniRoot, "CoordinateSystems"); coordCloud = childDir(coordSys!!, "cloud") }
                writeInto(coordSys!!, f.path, f.text)
                writeInto(coordCloud!!, f.path, f.text)   // ook in \cloud\ zodat Unicontrol het zeker vindt
            } else {
                writeInto(werfDir, f.path, f.text)
            }
        }
    }

    /** Zoekt de geïnstalleerde Unicontrol-app (via launcher-query) en start ze. */
    private fun launchUnicontrol() {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ri = packageManager.queryIntentActivities(main, 0).firstOrNull { it.activityInfo.packageName.contains("unicontrol", ignoreCase = true) }
        val intent = ri?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK); startActivity(intent) }
        else Toast.makeText(this, "Unicontrol-app niet gevonden op deze tablet", Toast.LENGTH_LONG).show()
    }

    private fun folderLabel(tree: String) = if (tree.isBlank()) "" else (Uri.parse(tree).lastPathSegment ?: tree)

    private fun extractCode(raw: String): String {
        val t = raw.trim()
        return try { val u = Uri.parse(t); u.getQueryParameter("connection_code") ?: u.getQueryParameter("code") ?: t } catch (_: Exception) { t }
    }

    private fun requestRuntimePerms(launcher: ActivityResultLauncher<Array<String>>) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}

// ── Herbruikbare stukjes ──
@Composable
private fun Logo(size: Int = 64) {
    Image(
        painter = painterResource(id = R.mipmap.ic_launcher),
        contentDescription = null,
        modifier = Modifier.size(size.dp).shadow(6.dp, RoundedCornerShape((size * 0.22f).dp), clip = false),
    )
}

@Composable
private fun SectionCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
                Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
            }
            content()
        }
    }
}

@Composable
private fun StatusPill(ok: Boolean, busy: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Gold)
            ok -> Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF43C98A), modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Startscherm / login ──
@Composable
fun HomeScreen(version: String, coupled: Boolean, code: String, machineName: String?, onPair: () -> Unit, onConvert: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Logo(80)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MV3D", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Surface(color = Gold.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                Text("BÈTA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Gold, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
        Text("Machinemanagement", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))

        // Machinist doet niks — enkel de verbonden-status.
        if (coupled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF43C98A), modifier = Modifier.size(18.dp))
                Text("Verbonden met MV3D", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!machineName.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(id = R.drawable.ic_excavator), contentDescription = null, modifier = Modifier.size(34.dp))
                Text(machineName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Gold)
            }
            if (code.isNotBlank()) Text(code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedButton(onPair, Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Outlined.SettingsRemote, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.size(10.dp))
                Text("Tablet koppelen (baas)")
            }
        }

        Spacer(Modifier.height(10.dp))
        ConverterSection(onBrand = onConvert)

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
            Text("Patent aangevraagd · technologie beschermd", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "MV3D · $version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onPair() }) },
        )
        Spacer(Modifier.height(12.dp))
    }
}

// ── Converter-wizard (bron → Unicontrol-project) ──
@Composable
private fun ConverterScreen(
    brand: String,
    sources: List<Pair<String, android.net.Uri>>,
    werf: String,
    uniChosen: Boolean,
    busy: Boolean, done: Boolean, message: String?,
    onBack: () -> Unit,
    onPickSources: () -> Unit,
    onPickUni: () -> Unit,
    onWerf: (String) -> Unit,
    onConvert: () -> Unit,
    onLaunchUni: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Terug", tint = MaterialTheme.colorScheme.onSurface) }
            Logo(40)
            Column { Text("Converteren → Unicontrol", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Bron: $brand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        SectionCard(Icons.Outlined.FolderOpen, "Bronbestand(en) — $brand") {
            Button(onPickSources, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Bestand(en) zoeken op de tablet") }
            if (sources.isEmpty()) Text("Nog geen bestand gekozen. Trimble: .svl + .svd · Topcon: .tp3 · Leica: .dxf + .xml", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else sources.forEach { (n, _) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF43C98A), modifier = Modifier.size(16.dp)); Text(n, style = MaterialTheme.typography.bodyMedium) } }
        }

        SectionCard(Icons.Outlined.SettingsRemote, "Unicontrol-map") {
            if (uniChosen) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF43C98A), modifier = Modifier.size(18.dp)); Text("Unicontrol-map gekozen", style = MaterialTheme.typography.bodyMedium) }
            else { Text("Kies één keer de Unicontrol-map (die met de map Projects). De app onthoudt ze.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); OutlinedButton(onPickUni, Modifier.fillMaxWidth()) { Text("Unicontrol-map kiezen") } }
        }

        SectionCard(Icons.Outlined.Link, "Werfnaam") {
            OutlinedTextField(werf, onWerf, label = { Text("Werfnaam") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Button(onConvert, Modifier.fillMaxWidth().height(52.dp), enabled = sources.isNotEmpty() && werf.isNotBlank() && uniChosen && !busy) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(8.dp)) }
            Text(if (busy) "Converteren…" else "Converteren & naar Unicontrol")
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = if (message.startsWith("Fout")) MaterialTheme.colorScheme.error else Color(0xFF43C98A))
        if (done) {
            Text("Tip: sluit Unicontrol eerst volledig af (uit recente apps vegen) — anders ziet het het nieuwe project niet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onLaunchUni, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Start Unicontrol") }
        }
    }
}

// ── Bestandsconvertor: merk-tegels op het startscherm ──
@Composable
private fun ConverterSection(onBrand: (String) -> Unit) {
    val ctx = LocalContext.current
    val brands = listOf(
        "Unicontrol" to R.drawable.tile_unicontrol,
        "Topcon" to R.drawable.tile_topcon,
        "Leica" to R.drawable.tile_leica,
        "Trimble" to R.drawable.tile_trimble,
        "CHCNAV" to R.drawable.tile_chcnav,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
            Text("BESTANDSCONVERTOR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, res) in brands) {
                Image(
                    painter = painterResource(id = res),
                    contentDescription = name,
                    modifier = Modifier.weight(1f).aspectRatio(1f)
                        .shadow(3.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (name == "Trimble" || name == "Topcon" || name == "Leica") onBrand(name)
                            else Toast.makeText(ctx, "$name-conversie: binnenkort", Toast.LENGTH_SHORT).show()
                        },
                )
            }
        }
    }
}

// ── Koppel-/machinist-scherm in blokken ──
@Composable
fun PairingScreen(
    code: String, folderLabel: String, coupled: Boolean,
    running: Boolean, status: String,
    onCode: (String) -> Unit,
    onPickFolder: () -> Unit, onStartSync: () -> Unit, onStopSync: () -> Unit,
    onScan: () -> Unit = {}, onOpenPortal: () -> Unit = {}, onBack: () -> Unit = {},
    syncBusy: Boolean = false, version: String = "",
    updateAvailable: String? = null, updateBusy: Boolean = false, updateError: String? = null, onUpdate: () -> Unit = {},
    codeField: String = code,
    authEmail: String = "", onLogout: () -> Unit = {},
) {
    val green = Color(0xFF43C98A)
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Terug", tint = MaterialTheme.colorScheme.onSurface) }
            Logo(40)
            Column { Text("MV3D", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Machine koppelen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (updateAvailable != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nieuwe versie beschikbaar ($updateAvailable)", style = MaterialTheme.typography.titleSmall)
                    Button(onUpdate, Modifier.fillMaxWidth(), enabled = !updateBusy) {
                        if (updateBusy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(8.dp)) }
                        Text(if (updateBusy) "Downloaden…" else "Bijwerken")
                    }
                    if (updateError != null) Text(updateError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        SectionCard(Icons.Outlined.Link, "Koppeling") {
            OutlinedTextField(codeField, onCode, label = { Text("Koppelcode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onScan, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("QR-code scannen") }
            Text("Wordt automatisch bewaard.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(Icons.Outlined.FolderOpen, "Doelmap besturingssoftware") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (folderLabel.isNotBlank()) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = green, modifier = Modifier.size(18.dp))
                Text(folderLabel.ifBlank { "— nog geen map gekozen —" }, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onPickFolder, Modifier.fillMaxWidth()) { Text(if (folderLabel.isBlank()) "Map kiezen" else "Andere map kiezen") }
        }

        SectionCard(Icons.Outlined.Sync, "Sync") {
            StatusPill(ok = running, busy = syncBusy, text = if (running) "Sync actief" else if (syncBusy) "Verbinden…" else "Gestopt")
            Text("Status: $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!coupled) Text(if (code.isBlank()) "→ Vul eerst de koppelcode in." else "→ Kies eerst de doelmap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onStartSync, Modifier.fillMaxWidth(), enabled = coupled && !running && !syncBusy) {
                if (running) { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)) }
                Text(if (running) "Sync actief" else if (syncBusy) "Verbinden…" else "Sync starten")
            }
            if (running || syncBusy) OutlinedButton(onStopSync, Modifier.fillMaxWidth()) { Text("Sync stoppen") }
        }

        Text("Scherm delen wordt volledig door de baas vanaf het portaal gestart — de machinist hoeft hier niets te doen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedButton(onOpenPortal, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("MV3D-portaal openen") }

        SectionCard(Icons.Outlined.AccountCircle, "Account") {
            Text(if (authEmail.isNotBlank()) "Ingelogd als $authEmail" else "Niet ingelogd (overgeslagen)", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onLogout, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Uitloggen") }
        }
        Text("MV3D · $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Preview(name = "Home", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun PreviewHome() { MaterialTheme(colorScheme = Mv3dColors) { Surface(color = MaterialTheme.colorScheme.background) { HomeScreen("build 12", coupled = true, code = "C7K5RYC7", machineName = "Kraan 12", onPair = {}, onConvert = {}) } } }

@Preview(name = "Koppelen", showBackground = true, widthDp = 420, heightDp = 1200)
@Composable
private fun PreviewPair() {
    MaterialTheme(colorScheme = Mv3dColors) {
        Surface(color = MaterialTheme.colorScheme.background) {
            PairingScreen(
                code = "KRAAN-12", folderLabel = "Trimble / Earthworks", coupled = true,
                running = true, status = "ok · 2 bestand(en) · TRIMBLE",
                onCode = {}, onPickFolder = {}, onStartSync = {}, onStopSync = {},
                version = "build 12",
            )
        }
    }
}
