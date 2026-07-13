package be.mv3d.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Interactieve kaart identiek aan het portaal: Leaflet (lokaal, inline uit de assets) met
 * ArcGIS-satelliettegels + labels-referentielaag. Kraan = groene pin, werven = oranje,
 * actieve werf = pulserende rode stip. Onderaan een diagnose-regel (JS-status/console).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalMap(
    machineName: String,
    mLat: Double?,
    mLon: Double?,
    werven: List<Werf>,
    onOpenWerf: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val data = ptsJson(machineName, mLat, mLon, werven)
    val css = remember { readAsset(ctx, "leaflet.css") }
    val js = remember { readAsset(ctx, "leaflet.js") }
    var status by remember { mutableStateOf("kaart laden…") }
    val main = remember { Handler(Looper.getMainLooper()) }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.allowFileAccess = true
                    setBackgroundColor(0xFFF6F8FB.toInt())
                    WebView.setWebContentsDebuggingEnabled(true)
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            main.post { status = "JS ${m.lineNumber()}: ${m.message()}" }
                            return true
                        }
                    }
                    setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                    addJavascriptInterface(object {
                        @JavascriptInterface fun openWerf(name: String) { main.post { onOpenWerf(name) } }
                        @JavascriptInterface fun status(s: String) { main.post { status = s } }
                    }, "Android")
                    tag = data
                    loadDataWithBaseURL("https://mv3d.be", buildHtml(data, css, js), "text/html", "utf-8", null)
                }
            },
            update = { wv ->
                if (wv.tag != data) {
                    wv.tag = data
                    wv.evaluateJavascript("window.setData && setData($data);", null)
                }
            },
        )
        Text(
            status,
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart)
                .background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun readAsset(ctx: Context, name: String): String =
    try { ctx.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { "" }

private fun ptsJson(machineName: String, mLat: Double?, mLon: Double?, werven: List<Werf>): String {
    val items = ArrayList<String>()
    if (mLat != null && mLon != null)
        items.add("""{"lat":$mLat,"lon":$mLon,"name":${JSONObject.quote(machineName.ifBlank { "Kraan" })},"machine":true,"active":false}""")
    for (w in werven) {
        val la = w.lat; val lo = w.lon
        if (la != null && lo != null)
            items.add("""{"lat":$la,"lon":$lo,"name":${JSONObject.quote(w.name)},"machine":false,"active":${w.current}}""")
    }
    return items.joinToString(",", "[", "]")
}

private fun buildHtml(data: String, css: String, js: String): String {
    val safeJs = js.replace("</script>", "<\\/script>")
    return """<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>$css
html,body{height:100%;margin:0;background:#F6F8FB}
#map{position:absolute;top:0;bottom:0;left:0;right:0}
.lp b{font-size:14px}.lp a{color:#E30613;font-weight:700;text-decoration:none}
.pulse{width:16px;height:16px;border-radius:50%;background:#E30613;border:3px solid #fff;box-shadow:0 0 0 0 rgba(227,6,19,.6);animation:pl 1.6s infinite}
@keyframes pl{0%{box-shadow:0 0 0 0 rgba(227,6,19,.6)}70%{box-shadow:0 0 0 18px rgba(227,6,19,0)}100%{box-shadow:0 0 0 0 rgba(227,6,19,0)}}</style>
<script>$safeJs</script>
</head><body><div id="map"></div><script>
function st(s){try{Android.status(s)}catch(e){}}
try{
  if(typeof L==='undefined'){ st('FOUT: Leaflet niet geladen'); }
  else {
    var map = L.map('map',{zoomControl:true,attributionControl:false}).setView([50.85,3.4],9);
    L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:22,maxNativeZoom:18}).addTo(map);
    L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}',{maxZoom:22,maxNativeZoom:18}).addTo(map);
    var layer = L.layerGroup().addTo(map); var didFit=false;
    function dot(c){return L.divIcon({className:'',html:'<div style="width:18px;height:18px;border-radius:50%;background:'+c+';border:3px solid #fff;box-shadow:0 1px 5px rgba(0,0,0,.5)"></div>',iconSize:[18,18],iconAnchor:[9,9]});}
    function pulse(){return L.divIcon({className:'',html:'<div class="pulse"></div>',iconSize:[16,16],iconAnchor:[8,8]});}
    function esc(s){return String(s).replace(/\\/g,'\\\\').replace(/'/g,"\\'");}
    window.setData = function(pts){
      layer.clearLayers(); var b=[];
      pts.forEach(function(p){
        var ic = p.machine ? dot('#20C95A') : (p.active ? pulse() : dot('#FF8A00'));
        var m=L.marker([p.lat,p.lon],{icon:ic}).addTo(layer);
        if(p.machine){ m.bindPopup('<div class="lp"><b>'+p.name+'</b><br>Kraan</div>'); }
        else { m.bindPopup('<div class="lp"><b>'+p.name+'</b>'+(p.active?' <span style="color:#E30613">&bull; actief</span>':'')+'<br><a href="#" onclick="Android.openWerf(\''+esc(p.name)+'\');return false;">Open werf &rarr;</a></div>'); }
        b.push([p.lat,p.lon]);
      });
      if(!didFit && b.length){ if(b.length==1){map.setView(b[0],15);} else {map.fitBounds(b,{padding:[45,45]});} didFit=true; }
      map.invalidateSize();
      var s=map.getSize();
      st('OK · '+pts.length+' pt · '+Math.round(s.x)+'x'+Math.round(s.y));
    };
    setData(/*PTS*/$data);
    setTimeout(function(){map.invalidateSize();},300);
    setTimeout(function(){map.invalidateSize();var s=map.getSize();st('kaart '+Math.round(s.x)+'x'+Math.round(s.y));},1200);
    setTimeout(function(){map.invalidateSize();},2600);
    window.addEventListener('resize',function(){map.invalidateSize();});
  }
}catch(e){ st('FOUT: '+e.message); }
</script></body></html>"""
}
