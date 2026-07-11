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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val code by prefs.codeFlow.collectAsState(initial = "")
                val tree by prefs.treeFlow.collectAsState(initial = "")

                var codeField by remember(code) { mutableStateOf(code) }
                var running by remember { mutableStateOf(SyncService.running) }
                var status by remember { mutableStateOf(SyncService.lastStatus) }
                var remoteStatus by remember { mutableStateOf(RemoteService.status) }
                var syncStarting by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { while (true) { status = SyncService.lastStatus; running = SyncService.running; remoteStatus = RemoteService.status; if (running) syncStarting = false; kotlinx.coroutines.delay(700) } }

                // auto-update: check de laatste GitHub-release bij het openen
                var update by remember { mutableStateOf<Updater.Update?>(null) }
                var updBusy by remember { mutableStateOf(false) }
                var updErr by remember { mutableStateOf<String?>(null) }
                var updDismissed by remember { mutableStateOf(false) }
                // check bij openen + elke 60s (pikt een nieuwe release op zonder herstart)
                LaunchedEffect(Unit) { while (true) { runCatching { withContext(Dispatchers.IO) { Updater.check() } }.getOrNull()?.let { update = it }; kotlinx.coroutines.delay(60_000) } }
                LaunchedEffect(update?.versionCode) { if (update != null) updDismissed = false } // nieuwe versie → pop-up opnieuw tonen

                val doUpdate: () -> Unit = {
                    val u = update
                    if (u != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                            updErr = "Zet 'installeren van onbekende apps' AAN voor MV3D Machine, en tik dan opnieuw op Bijwerken."
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
                    result.contents?.let { raw ->
                        val c = extractCode(raw); codeField = c
                        scope.launch { prefs.setCode(c) }
                    }
                }

                // Gekoppeld? Start de sync automatisch — de machinist hoeft niets te doen.
                LaunchedEffect(code, tree) {
                    if (code.isNotBlank() && tree.isNotBlank() && !SyncService.running) {
                        requestRuntimePerms(askPerms)
                        startSvc(SyncService::class.java)
                    }
                }

                PairingScreen(
                    code = code, folderLabel = folderLabel(tree),
                    coupled = code.isNotBlank() && tree.isNotBlank(),
                    running = running, status = status, remoteStatus = remoteStatus,
                    onCode = { codeField = it; scope.launch { prefs.setCode(it) } },      // automatisch bewaren
                    onScan = { scan.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false).setPrompt("Scan de koppelcode-QR")) },
                    version = "build ${BuildConfig.VERSION_CODE}",
                    updateAvailable = update?.versionName, updateBusy = updBusy, updateError = updErr,
                    onUpdate = doUpdate,
                    onPickFolder = { pickTree.launch(null) },
                    syncBusy = syncStarting,
                    onStartSync = { syncStarting = true; requestRuntimePerms(askPerms); startSvc(SyncService::class.java) },
                    onStopSync = { syncStarting = false; stopService(Intent(this@MainActivity, SyncService::class.java)) },
                    onStartRemote = { startSvc(RemoteService::class.java) },
                    onStopRemote = { stopService(Intent(this@MainActivity, RemoteService::class.java)) },
                    codeField = codeField,
                )

                // pop-up bij een beschikbare update
                val up = update
                if (up != null && !updDismissed) {
                    AlertDialog(
                        onDismissRequest = { updDismissed = true },
                        title = { Text("Nieuwe versie beschikbaar") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Er staat een update klaar (${up.versionName}). Nu bijwerken?"); if (updErr != null) Text(updErr!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } },
                        confirmButton = { Button(onClick = doUpdate, enabled = !updBusy) { if (updBusy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)) }; Text(if (updBusy) "Downloaden…" else "Bijwerken") } },
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

    private fun folderLabel(tree: String) = if (tree.isBlank()) "" else (Uri.parse(tree).lastPathSegment ?: tree)

    /** Uit een gescande QR de koppelcode halen: uit een URL-parameter (code/connection_code) of ruw. */
    private fun extractCode(raw: String): String {
        val t = raw.trim()
        return try {
            val u = Uri.parse(t)
            u.getQueryParameter("connection_code") ?: u.getQueryParameter("code") ?: t
        } catch (_: Exception) { t }
    }

    private fun requestRuntimePerms(launcher: ActivityResultLauncher<Array<String>>) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}

/** Stateless scherm — previewbaar in Android Studio zonder toestel. */
@Composable
fun PairingScreen(
    code: String, folderLabel: String, coupled: Boolean,
    running: Boolean, status: String, remoteStatus: String,
    onCode: (String) -> Unit,
    onPickFolder: () -> Unit, onStartSync: () -> Unit, onStopSync: () -> Unit,
    onStartRemote: () -> Unit, onStopRemote: () -> Unit,
    onScan: () -> Unit = {},
    syncBusy: Boolean = false,
    version: String = "",
    updateAvailable: String? = null, updateBusy: Boolean = false, updateError: String? = null, onUpdate: () -> Unit = {},
    codeField: String = code,
) {
    val green = Color(0xFF2E7D32)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MV3D Machine", style = MaterialTheme.typography.headlineSmall)
            Text("Vul de koppelcode in en kies de map — de rest gebeurt automatisch.", style = MaterialTheme.typography.bodyMedium)

            if (updateAvailable != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nieuwe versie beschikbaar", style = MaterialTheme.typography.titleMedium)
                        Text(updateAvailable, style = MaterialTheme.typography.bodySmall)
                        Button(onUpdate, Modifier.fillMaxWidth(), enabled = !updateBusy) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (updateBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(if (updateBusy) "Downloaden…" else "Bijwerken")
                            }
                        }
                        if (updateError != null) Text(updateError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            OutlinedTextField(codeField, onCode, label = { Text("Koppelcode (connection code)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onScan, Modifier.fillMaxWidth()) { Text("QR-code scannen") }
            Text("Wordt automatisch bewaard.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()
            Text("Doelmap besturingssoftware", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (folderLabel.isNotBlank()) Text("✓", color = green, style = MaterialTheme.typography.titleMedium)
                Text(folderLabel.ifBlank { "— nog geen map gekozen —" }, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onPickFolder, Modifier.fillMaxWidth()) { Text(if (folderLabel.isBlank()) "Map kiezen" else "Andere map kiezen") }

            HorizontalDivider()
            // Sync-knop met duidelijke toestand: grijs+reden / zandloper / groen vinkje
            Button(onStartSync, Modifier.fillMaxWidth(), enabled = coupled && !running && !syncBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) Text("✓", color = green)
                    else if (syncBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (running) "Sync actief" else if (syncBusy) "Verbinden…" else "Sync starten")
                }
            }
            if (!coupled) Text(if (code.isBlank()) "→ Vul eerst de koppelcode in." else "→ Kies eerst de doelmap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (running || syncBusy) OutlinedButton(onStopSync, Modifier.fillMaxWidth()) { Text("Sync stoppen") }
            ListItem(
                leadingContent = { if (running) Text("✓", color = green, style = MaterialTheme.typography.titleLarge) else if (syncBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                headlineContent = { Text(if (running) "Sync actief" else if (syncBusy) "Verbinden…" else "Sync gestopt") },
                supportingContent = { Text("Status: $status") },
            )

            HorizontalDivider()
            Text("Scherm delen (bediening op afstand)", style = MaterialTheme.typography.titleMedium)
            Text("Wordt normaal door de baas gestart vanaf het platform. Vereist droidVNC-NG.", style = MaterialTheme.typography.bodySmall)
            Button(onStartRemote, Modifier.fillMaxWidth(), enabled = code.isNotBlank()) { Text("Scherm delen starten") }
            OutlinedButton(onStopRemote, Modifier.fillMaxWidth()) { Text("Scherm delen stoppen") }
            ListItem(headlineContent = { Text("Remote: $remoteStatus") })

            Text("MV3D Machine · $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Preview(name = "Gekoppeld · sync actief", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun PreviewCoupled() {
    MaterialTheme {
        PairingScreen(
            code = "KRAAN-7F3A-92", folderLabel = "Trimble / Earthworks / Designs",
            coupled = true, running = true, status = "ok · 2 bestand(en) · 1 cmd · TRIMBLE_EARTHWORKS",
            remoteStatus = "actief · https://calm-river-8821.trycloudflare.com",
            onCode = {}, onPickFolder = {}, onStartSync = {}, onStopSync = {}, onStartRemote = {}, onStopRemote = {},
        )
    }
}

@Preview(name = "Nieuw · niet gekoppeld", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun PreviewEmpty() {
    MaterialTheme {
        PairingScreen(
            code = "", folderLabel = "",
            coupled = false, running = false, status = "niet gekoppeld", remoteStatus = "uit",
            onCode = {}, onPickFolder = {}, onStartSync = {}, onStopSync = {}, onStartRemote = {}, onStopRemote = {},
        )
    }
}
