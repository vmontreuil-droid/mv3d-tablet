package be.mv3d.tablet

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Interactieve kaart identiek aan het portaal: Leaflet met ArcGIS-satelliettegels +
 * labels-referentielaag. Kraan = groene pin, werven = oranje pins. Op een werf-pin
 * klikken toont de naam + "Open werf →" (→ onOpenWerf).
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
    val data = ptsJson(machineName, mLat, mLon, werven)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                // de kaart (pan/zoom) krijgt de vingerbewegingen, niet de pagina-scroll eronder
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun openWerf(name: String) { Handler(Looper.getMainLooper()).post { onOpenWerf(name) } }
                }, "Android")
                loadDataWithBaseURL("https://mv3d.be", LEAFLET_HTML, "text/html", "utf-8", null)
                tag = ""
            }
        },
        update = { wv ->
            // markers bijwerken zonder de kaart te resetten (behoudt zoom/positie)
            if (wv.tag != data) {
                wv.tag = data
                wv.evaluateJavascript("window.setData && setData($data);", null)
            }
        },
    )
}

private fun ptsJson(machineName: String, mLat: Double?, mLon: Double?, werven: List<Werf>): String {
    val items = ArrayList<String>()
    if (mLat != null && mLon != null)
        items.add("""{"lat":$mLat,"lon":$mLon,"name":${JSONObject.quote(machineName.ifBlank { "Kraan" })},"machine":true}""")
    for (w in werven) {
        val la = w.lat; val lo = w.lon
        if (la != null && lo != null)
            items.add("""{"lat":$la,"lon":$lo,"name":${JSONObject.quote(w.name)},"machine":false}""")
    }
    return items.joinToString(",", "[", "]")
}

private const val LEAFLET_HTML = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#map{height:100%;margin:0;background:#F6F8FB}
.lp b{font-size:14px}.lp a{color:#E30613;font-weight:700;text-decoration:none}</style>
</head><body><div id="map"></div><script>
var map = L.map('map',{zoomControl:true,attributionControl:false}).setView([50.85,3.4],9);
L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:22,maxNativeZoom:18}).addTo(map);
L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}',{maxZoom:22,maxNativeZoom:18}).addTo(map);
var layer = L.layerGroup().addTo(map); var didFit=false;
function dot(c){return L.divIcon({className:'',html:'<div style="width:18px;height:18px;border-radius:50%;background:'+c+';border:3px solid #fff;box-shadow:0 1px 5px rgba(0,0,0,.5)"></div>',iconSize:[18,18],iconAnchor:[9,9]});}
function esc(s){return String(s).replace(/\\/g,'\\\\').replace(/'/g,"\\'");}
function setData(pts){
  layer.clearLayers(); var b=[];
  pts.forEach(function(p){
    var m=L.marker([p.lat,p.lon],{icon:dot(p.machine?'#20C95A':'#FF8A00')}).addTo(layer);
    if(p.machine){ m.bindPopup('<div class="lp"><b>'+p.name+'</b><br>Kraan</div>'); }
    else { m.bindPopup('<div class="lp"><b>'+p.name+'</b><br><a href="#" onclick="Android.openWerf(\''+esc(p.name)+'\');return false;">Open werf &rarr;</a></div>'); }
    b.push([p.lat,p.lon]);
  });
  if(!didFit && b.length){ if(b.length==1){map.setView(b[0],15);} else {map.fitBounds(b,{padding:[45,45]});} didFit=true; }
}
</script></body></html>"""
