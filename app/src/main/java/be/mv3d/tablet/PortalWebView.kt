package be.mv3d.tablet

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val PInk = Color(0xFF1A1D26); private val PMuted = Color(0xFF8B93A3)
private val PLine = Color(0xFFE9EDF3); private val PRed = Color(0xFFE30613); private val PPanel = Color(0xFFF6F8FB)

/**
 * Beheerder-thuis: het échte MV3D-portaal in een WebView (identiek + updatet vanzelf mee),
 * met een smalle native werkbalk erboven zodat de convertor/instellingen één tik weg blijven.
 */
@Composable
fun PortalHome(
    server: String, token: String, refresh: String, email: String,
    onConvert: (String) -> Unit, onSettings: () -> Unit, onLogout: () -> Unit,
) {
    // login-brug: zet de Supabase-sessie via de tokens in de hash en ga door naar /worksmanager
    val url = remember(server, token, refresh) {
        "$server/tablet-login#access_token=${Uri.encode(token)}&refresh_token=${Uri.encode(refresh)}&next=${Uri.encode("/worksmanager")}"
    }
    var reloadTick by remember { mutableStateOf(0) }

    Surface(color = Color(0xFFFAFBFC)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(painterResource(R.drawable.mv3d_logo), null, Modifier.size(26.dp))
                Column(Modifier.weight(1f)) {
                    Text("Portaal", color = PInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (email.isNotBlank()) Text(email, color = PMuted, fontSize = 10.sp, maxLines = 1)
                }
                ToolBtn(Icons.Outlined.SwapHoriz, "Convertor") { onConvert("Unicontrol") }
                ToolBtn(Icons.Outlined.Settings, "Instellingen") { onSettings() }
                ToolBtn(Icons.Outlined.Refresh, "Vernieuwen") { reloadTick++ }
                ToolBtn(Icons.Outlined.PowerSettingsNew, "Uitloggen") { onLogout() }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(PLine))
            PortalPane(url, reloadTick, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun ToolBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(PPanel).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = PRed, modifier = Modifier.size(17.dp))
        Text(label, color = PInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PortalPane(url: String, reloadTick: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val fileCb = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uris = if (res.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data) else null
        fileCb.value?.onReceiveValue(uris ?: arrayOf()); fileCb.value = null
    }
    // vernieuw-knop → herlaad de huidige portaalpagina (sessie blijft via cookies)
    LaunchedEffect(reloadTick) { if (reloadTick > 0) webRef?.reload() }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(c).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.allowFileAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val host = request.url.host ?: ""
                        if (host.contains("mv3d.be") || host.contains("trycloudflare.com") || host.contains("supabase")) return false
                        return try { c.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        fileCb.value?.onReceiveValue(null); fileCb.value = callback
                        return try { chooser.launch(params.createIntent()); true } catch (_: Exception) { fileCb.value = null; false }
                    }
                }
                webRef = this
                loadUrl(url)
            }
        },
    )
}
