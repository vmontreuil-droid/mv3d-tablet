package be.mv3d.tablet

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private val DBg = Color(0xFFFFFFFF); private val DPanel2 = Color(0xFFF6F8FB)
private val DInk = Color(0xFF1A1D26); private val DSoft = Color(0xFF586173); private val DMuted = Color(0xFF8B93A3); private val DLine = Color(0xFFE9EDF3)
private val DRed = Color(0xFFE30613); private val DRed2 = Color(0xFFFF3A47); private val DRedTint = Color(0xFFFFF5F6)
private val DGreen = Color(0xFF20C95A); private val DOn = Color(0xFF12A150); private val DOnBg = Color(0xFFE3F6EA)
private val DOrange = Color(0xFFFF8A00)

private fun gsColors(gs: String): Pair<Color, Color> = when (gs.uppercase()) {
    "UNICONTROL" -> Color(0xFF1A86C8) to Color(0xFFE6F2FB)
    "TRIMBLE" -> Color(0xFF2A52BE) to Color(0xFFE5ECFB)
    "TOPCON" -> Color(0xFFC0342C) to Color(0xFFFDE7E7)
    "LEICA" -> Color(0xFF1A8A4E) to Color(0xFFE2F5EA)
    else -> Color(0xFF586173) to Color(0xFFEEF1F5)
}

@Composable
fun DashboardScreen(
    server: String, code: String, machineName: String,
    onSettings: () -> Unit, onConvert: (String) -> Unit, onLogout: () -> Unit,
) {
    var ov by remember { mutableStateOf<Overview?>(null) }
    val werfBmps = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(code) {
        val api = Api(server, code)
        while (true) {   // elke 15s vernieuwen → nieuwe werven/GPS verschijnen vanzelf
            val o = withContext(Dispatchers.IO) { runCatching { api.overview() }.getOrNull() }
            if (o != null) {
                ov = o
                // werf-luchtfoto's parallel ophalen (veel sneller dan één voor één)
                val todo = o.werven.filter { it.lat != null && it.lon != null && !werfBmps.containsKey(it.name) }
                if (todo.isNotEmpty()) coroutineScope {
                    todo.map { w ->
                        async(Dispatchers.IO) { w.name to api.aerial(w.lat!!, w.lon!!, 400, 240) }
                    }.forEach { job ->
                        val (nm, b) = job.await()
                        if (b != null) runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()?.let { werfBmps[nm] = it }
                    }
                }
            }
            kotlinx.coroutines.delay(15_000)
        }
    }

    val name = (ov?.name?.takeIf { it.isNotBlank() } ?: machineName.takeIf { it.isNotBlank() }) ?: "Kraan"
    val gs = ov?.guidance?.takeIf { it.isNotBlank() } ?: "—"
    val (gsFg, gsBg) = gsColors(gs)
    val werven = (ov?.werven ?: emptyList()).sortedByDescending { it.current }  // actieve werf eerst
    var navOpen by remember { mutableStateOf(false) }  // menu ingeklapt bij opstart
    var view by remember { mutableStateOf("werven") }  // "kraan" | "werven" — start op werven (kaart + tegels)
    var selectedWerf by remember { mutableStateOf<String?>(null) }
    val activeWerfName = werven.firstOrNull { it.current }?.name ?: werven.firstOrNull()?.name ?: "—"
    // menu klapt automatisch weer dicht na 5s
    LaunchedEffect(navOpen) { if (navOpen) { kotlinx.coroutines.delay(5000); navOpen = false } }

    Surface(color = DBg) {
        Row(Modifier.fillMaxSize().statusBarsPadding()) {
            // ── SIDEBAR ──
            if (navOpen) {
            Column(Modifier.width(212.dp).fillMaxHeight().background(Color.White).padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.padding(start = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.mv3d_logo), null, Modifier.size(34.dp))
                    Column { Text("MV3D", color = DRed, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text("Machineapp", color = DMuted, fontSize = 11.sp) }
                }
                NavItem(Icons.Outlined.Agriculture, "Mijn kraan", view == "kraan") { view = "kraan" }
                NavItem(Icons.Outlined.Foundation, "Werven", view == "werven") { view = "werven" }
                NavItem(Icons.Outlined.SwapHoriz, "Convertor", false) { onConvert("Unicontrol") }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DPanel2).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(DRedTint), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Agriculture, null, tint = DRed, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text("Ingelogd als kraan", color = DMuted, fontSize = 10.sp)
                            Text(name, color = DInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(DOn)); Text("Online · $gs", color = DOn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Kraancode", color = DMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 9.dp))
                    Text(code, color = DRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Actieve werf", color = DMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 9.dp))
                    Text(activeWerfName, color = DInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Werven", color = DMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 9.dp))
                    Text(werven.size.toString(), color = DInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                // QR-code van de kraancode (scannen om te koppelen)
                val qr = remember(code) { qrBitmap(code, 240) }
                if (qr != null) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DPanel2).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)) {
                            Image(qr, "QR-code kraancode", Modifier.size(132.dp))
                        }
                        Text("Scan om te koppelen", color = DMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                }
                NavItem(Icons.Outlined.Settings, "Instellingen", false) { onSettings() }
                NavItem(Icons.Outlined.PowerSettingsNew, "Uitloggen", false) { onLogout() }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(DLine))
            }

            // ── MAIN ──
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    if (!navOpen) Image(painterResource(R.drawable.mv3d_logo), null, Modifier.size(30.dp))  // logo zichtbaar bij gesloten sidebar
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(DPanel2).clickable { navOpen = !navOpen }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Menu, "Menu in-/uitklappen", tint = DInk, modifier = Modifier.size(20.dp))
                    }
                    Text("MENU", color = DMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    // actieve werf rechtsboven met rood bolletje
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(DRedTint).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(DRed))
                        Text(activeWerfName, color = DRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                val sel = selectedWerf
                if (sel != null) WerfDetail(werven.firstOrNull { it.name == sel }, sel, name, ov?.lat, ov?.lon) { selectedWerf = null }
                else {
                // grote overzichtskaart bovenaan (enkel in Werven-view) — volledige breedte, met de werven erop
                if (view == "werven") Card(Modifier.fillMaxWidth().height(400.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, DLine)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocationOn, null, tint = DGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
                            Text("Kraan & Werven", color = DInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(DGreen)); Spacer(Modifier.size(4.dp))
                            Text("Kraan", color = DMuted, fontSize = 11.sp); Spacer(Modifier.size(10.dp))
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(DOrange)); Spacer(Modifier.size(4.dp))
                            Text("Werven", color = DMuted, fontSize = 11.sp)
                        }
                        PortalMap(name, ov?.lat, ov?.lon, werven, onOpenWerf = { selectedWerf = it }, modifier = Modifier.fillMaxSize())
                    }
                }

                if (view == "kraan") {
                    // kraan-info staat enkel in de zijbalk — deze pagina configureren we later
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, DLine)) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Tune, null, tint = DMuted, modifier = Modifier.size(28.dp))
                            Text("Mijn kraan", color = DInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Deze pagina configureren we later.", color = DMuted, fontSize = 13.sp)
                        }
                    }
                }

                // ── WERVEN ──
                if (view == "werven" && werven.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                      werven.chunked(3).forEach { rij ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (w in rij) {
                            Card(Modifier.weight(1f).clickable { selectedWerf = w.name }, colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, DLine)) {
                                Column {
                                    Box(Modifier.fillMaxWidth().height(108.dp), contentAlignment = Alignment.Center) {
                                        val wb = werfBmps[w.name]
                                        if (wb != null) Image(wb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        else Box(Modifier.fillMaxSize().background(DPanel2))
                                        // oranje bolletje op het werfadres (enkel als de werf een locatie heeft)
                                        if (w.lat != null && w.lon != null)
                                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(Color.White), contentAlignment = Alignment.Center) { Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(DOrange)) }
                                        // rode "Actief"-hoek als deze werf open staat in Unicontrol
                                        if (w.current)
                                            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(DRed).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                                Text("ACTIEF", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                            }
                                    }
                                    Column(Modifier.padding(13.dp)) {
                                        Text(w.name, color = DInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text(w.address.ifBlank { if (w.active) "Actief" else "Klaar" }, color = DMuted, fontSize = 12.sp)
                                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("${w.files} bestanden", color = DSoft, fontSize = 12.sp)
                                            Text("Openen →", color = DRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        repeat(3 - rij.size) { Spacer(Modifier.weight(1f)) }
                        }
                      }
                    }
                }

                // (Bestandsconvertor komt later terug onder de Convertor-tab / bij nieuwe werf.)
                Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Werf-detail: kaart + de projecten (ontwerpen) van deze werf. */
@Composable
private fun WerfDetail(w: Werf?, werfName: String, machineName: String, mLat: Double?, mLon: Double?, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(DPanel2).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ChevronLeft, "Terug", tint = DInk, modifier = Modifier.size(22.dp))
            }
            Column {
                Text("WERF", color = DMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(werfName, color = DInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (w == null) { Text("Werf niet gevonden", color = DMuted, fontSize = 14.sp); return@Column }
        if (w.address.isNotBlank()) Text(w.address, color = DSoft, fontSize = 13.sp)

        // kaart van deze werf (+ de kraan)
        Card(Modifier.fillMaxWidth().height(300.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, DLine)) {
            PortalMap(machineName, mLat, mLon, listOf(w), onOpenWerf = {}, modifier = Modifier.fillMaxSize())
        }

        // projecten (ontwerpen) in de werf
        Text("PROJECTEN · ${w.projecten.size}", color = DMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        if (w.projecten.isEmpty()) {
            Text("Nog geen projecten in deze werf.", color = DMuted, fontSize = 13.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in w.projecten) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, DLine)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(DRedTint), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Description, null, tint = DRed, modifier = Modifier.size(20.dp))
                            }
                            Text(p.name, color = DInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(DPanel2).padding(horizontal = 9.dp, vertical = 4.dp)) {
                                Text(p.type, color = DSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ColumnScope.Fact(k: String, v: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 11.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = DMuted, fontSize = 13.sp)
        if (mono) Box(Modifier.clip(RoundedCornerShape(6.dp)).background(DPanel2).padding(horizontal = 8.dp, vertical = 3.dp)) { Text(v, color = DRed, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
        else Text(v, color = DInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Overzichtskaart met luchtfoto: kraan (groen) + alle werven (oranje) op hun echte GPS-positie. */
@Composable
private fun OverviewMap(server: String, code: String, mLat: Double?, mLon: Double?, werven: List<Werf>) {
    // punten verzamelen (machine = groen, werven met locatie = oranje)
    data class P(val lat: Double, val lon: Double, val machine: Boolean)
    val pts = buildList {
        if (mLat != null && mLon != null) add(P(mLat, mLon, true))
        for (w in werven) { val la = w.lat; val lo = w.lon; if (la != null && lo != null) add(P(la, lo, false)) }
    }
    if (pts.isEmpty()) {
        Box(Modifier.fillMaxSize().background(DPanel2), contentAlignment = Alignment.Center) { Text("Geen locatie bekend", color = DMuted, fontSize = 13.sp) }
        return
    }
    // visuele coördinaten (lengtegraad indrukken met cos(breedte) zodat de kaart niet vervormt)
    val cosLat = 0.63
    val vxs = pts.map { it.lon * cosLat }; val vys = pts.map { it.lat }
    val cVX = (vxs.min() + vxs.max()) / 2.0; val cVY = (vys.min() + vys.max()) / 2.0

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val aspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 2f
        val boxW = maxWidth; val boxH = maxHeight   // opvangen: niet bereikbaar in geneste Box-scope
        var spanVX = (vxs.max() - vxs.min()).coerceAtLeast(0.0028) * 1.4
        var spanVY = (vys.max() - vys.min()).coerceAtLeast(0.0016) * 1.4
        if (spanVX / spanVY < aspect) spanVX = spanVY * aspect else spanVY = spanVX / aspect

        val west = (cVX - spanVX / 2) / cosLat; val east = (cVX + spanVX / 2) / cosLat
        val south = cVY - spanVY / 2; val north = cVY + spanVY / 2

        var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(west, south, east, north) {
            val h = (1000f / aspect).toInt().coerceIn(300, 900)
            val b = withContext(Dispatchers.IO) { Api(server, code).aerialBbox(west, south, east, north, 1000, h) }
            if (b != null) runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()?.let { bmp = it }
        }
        // pinch-to-zoom + verschuiven met de vingers (dubbeltik = terug naar overzicht)
        var scale by remember { mutableStateOf(1f) }
        var off by remember { mutableStateOf(Offset.Zero) }
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        Box(
            Modifier.fillMaxSize().clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 6f)
                        val maxX = (ns - 1f) * wPx / 2f; val maxY = (ns - 1f) * hPx / 2f
                        val np = off + pan
                        off = Offset(np.x.coerceIn(-maxX, maxX), np.y.coerceIn(-maxY, maxY))
                        scale = ns
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; off = Offset.Zero }) }
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y }) {
                val mb = bmp
                if (mb != null) Image(mb, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                else Box(Modifier.fillMaxSize().background(DPanel2), contentAlignment = Alignment.Center) { Text("Kaart laden…", color = DMuted, fontSize = 13.sp) }

                for (p in pts) {
                    val fx = ((p.lon * cosLat - cVX) / spanVX + 0.5).coerceIn(0.03, 0.97).toFloat()
                    val fy = (0.5 - (p.lat - cVY) / spanVY).coerceIn(0.03, 0.97).toFloat()
                    val ring = if (p.machine) 20.dp else 16.dp
                    val inner = if (p.machine) 14.dp else 10.dp
                    val dot = if (p.machine) DGreen else DOrange
                    Box(
                        Modifier.offset(x = boxW * fx - ring / 2, y = boxH * fy - ring / 2)
                            .size(ring).clip(RoundedCornerShape(50)).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) { Box(Modifier.size(inner).clip(RoundedCornerShape(50)).background(dot)) }
                }
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) DRedTint else Color.Transparent).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, tint = if (on) DRed else DSoft, modifier = Modifier.size(19.dp))
        Text(label, color = if (on) DRed else DSoft, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
