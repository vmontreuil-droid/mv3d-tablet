package be.mv3d.tablet

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/** Toont het volledige MV3D-portaal (worksmanager) in een ingebouwde WebView. */
class WebViewActivity : ComponentActivity() {
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var web: WebView

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uris = if (res.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data) else null
        filePathCallback?.onReceiveValue(uris ?: arrayOf()); filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        android.webkit.CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) } // Supabase-sessie bewaren
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                 // nodig voor de Supabase-sessie (ingelogd blijven)
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: ""
                // eigen domeinen (incl. de VNC-tunnel) in de app houden; externe links naar de browser
                if (host.contains("mv3d.be") || host.contains("trycloudflare.com") || host.contains("supabase")) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true } catch (e: Exception) { false }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                filePathCallback?.onReceiveValue(null); filePathCallback = callback
                return try { fileChooser.launch(params.createIntent()); true } catch (e: Exception) { filePathCallback = null; false }
            }
        }
        web.setDownloadListener { url, _, _, _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }

        // terug-knop navigeert in de WebView i.p.v. de activiteit meteen te sluiten
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        web.loadUrl(intent.getStringExtra("url") ?: "https://mv3d.be/worksmanager")
    }
}
