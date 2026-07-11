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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val code by prefs.codeFlow.collectAsState(initial = "")
                val server by prefs.serverFlow.collectAsState(initial = "https://mv3d.be")
                val tree by prefs.treeFlow.collectAsState(initial = "")

                var codeField by remember(code) { mutableStateOf(code) }
                var serverField by remember(server) { mutableStateOf(server) }
                var running by remember { mutableStateOf(SyncService.running) }
                var status by remember { mutableStateOf(SyncService.lastStatus) }
                var remoteStatus by remember { mutableStateOf(RemoteService.status) }
                LaunchedEffect(Unit) { while (true) { status = SyncService.lastStatus; running = SyncService.running; remoteStatus = RemoteService.status; kotlinx.coroutines.delay(1000) } }

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
                    code = code, server = serverField, folderLabel = folderLabel(tree),
                    coupled = code.isNotBlank() && tree.isNotBlank(),
                    running = running, status = status, remoteStatus = remoteStatus,
                    onCode = { codeField = it }, onServer = { serverField = it },
                    onScan = { scan.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false).setPrompt("Scan de koppelcode-QR")) },
                    onSave = { scope.launch { prefs.setCode(codeField); prefs.setServer(serverField) } },
                    onPickFolder = { pickTree.launch(null) },
                    onStartSync = { requestRuntimePerms(askPerms); startSvc(SyncService::class.java) },
                    onStopSync = { stopService(Intent(this@MainActivity, SyncService::class.java)) },
                    onStartRemote = { startSvc(RemoteService::class.java) },
                    onStopRemote = { stopService(Intent(this@MainActivity, RemoteService::class.java)) },
                    codeField = codeField,
                )
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
    code: String, server: String, folderLabel: String, coupled: Boolean,
    running: Boolean, status: String, remoteStatus: String,
    onCode: (String) -> Unit, onServer: (String) -> Unit, onSave: () -> Unit,
    onPickFolder: () -> Unit, onStartSync: () -> Unit, onStopSync: () -> Unit,
    onStartRemote: () -> Unit, onStopRemote: () -> Unit,
    onScan: () -> Unit = {},
    codeField: String = code,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MV3D Machine", style = MaterialTheme.typography.headlineSmall)
            Text("Koppel deze tablet aan een machine en houd de sync actief.", style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(codeField, onCode, label = { Text("Koppelcode (connection code)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onScan, Modifier.fillMaxWidth()) { Text("QR-code scannen") }
            OutlinedTextField(server, onServer, label = { Text("Server") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Button(onSave, Modifier.fillMaxWidth()) { Text("Koppeling opslaan") }

            HorizontalDivider()
            Text("Doelmap besturingssoftware", style = MaterialTheme.typography.titleMedium)
            Text(folderLabel.ifBlank { "— nog geen map gekozen —" }, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onPickFolder, Modifier.fillMaxWidth()) { Text("Map kiezen") }

            HorizontalDivider()
            Button(onStartSync, Modifier.fillMaxWidth(), enabled = coupled) { Text("Sync starten") }
            OutlinedButton(onStopSync, Modifier.fillMaxWidth()) { Text("Sync stoppen") }
            ListItem(headlineContent = { Text(if (running) "Sync actief" else "Sync gestopt") }, supportingContent = { Text("Status: $status") })

            HorizontalDivider()
            Text("Scherm delen (bediening op afstand)", style = MaterialTheme.typography.titleMedium)
            Text("Vereist droidVNC-NG geïnstalleerd; de baas kijkt mee vanaf zijn desktop.", style = MaterialTheme.typography.bodySmall)
            Button(onStartRemote, Modifier.fillMaxWidth(), enabled = code.isNotBlank()) { Text("Scherm delen starten") }
            OutlinedButton(onStopRemote, Modifier.fillMaxWidth()) { Text("Scherm delen stoppen") }
            ListItem(headlineContent = { Text("Remote: $remoteStatus") })
        }
    }
}

@Preview(name = "Gekoppeld · sync actief", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun PreviewCoupled() {
    MaterialTheme {
        PairingScreen(
            code = "KRAAN-7F3A-92", server = "https://mv3d.be", folderLabel = "Trimble / Earthworks / Designs",
            coupled = true, running = true, status = "ok · 2 bestand(en) · 1 cmd · TRIMBLE_EARTHWORKS",
            remoteStatus = "actief · https://calm-river-8821.trycloudflare.com",
            onCode = {}, onServer = {}, onSave = {}, onPickFolder = {}, onStartSync = {}, onStopSync = {}, onStartRemote = {}, onStopRemote = {},
        )
    }
}

@Preview(name = "Nieuw · niet gekoppeld", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun PreviewEmpty() {
    MaterialTheme {
        PairingScreen(
            code = "", server = "https://mv3d.be", folderLabel = "",
            coupled = false, running = false, status = "niet gekoppeld", remoteStatus = "uit",
            onCode = {}, onServer = {}, onSave = {}, onPickFolder = {}, onStartSync = {}, onStopSync = {}, onStartRemote = {}, onStopRemote = {},
        )
    }
}
