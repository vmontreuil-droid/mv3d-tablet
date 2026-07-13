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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DBg = Color(0xFFFFFFFF); private val DPanel2 = Color(0xFFF6F8FB)
private val DInk = Color(0xFF1A1D26); private val DSoft = Color(0xFF586173); private val DMuted = Color(0xFF8B93A3); private val DLine = Color(0xFFE9EDF3)
private val DRed = Color(0xFFE30613); private val DRed2 = Color(0xFFFF3A47); private val DRedTint = Color(0xFFFFF5F6)
private val DGreen = Color(0xFF20C95A); private val DOn = Color(0xFF12A150); private val DOnBg = Color(0xFFE3F6EA)

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
    var mapBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    val werfBmps = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(code) {
        val api = Api(server, code)
        while (true) {   // elke 15s vernieuwen → nieuwe werven/GPS verschijnen vanzelf
            val o = withContext(Dispatchers.IO) { runCatching { api.overview() }.getOrNull() }
            if (o != null) {
                ov = o
                val la = o.lat; val lo = o.lon
                if (mapBmp == null && la != null && lo != null) {
                    val b = withContext(Dispatchers.IO) { api.aerial(la, lo, 1000, 340) }
                    if (b != null) runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()?.let { mapBmp = it }
                }
                for (w in o.werven) {
                    val wla = w.lat; val wlo = w.lon
                    if (!werfBmps.containsKey(w.name) && wla != null && wlo != null) {
                        val b = withContext(Dispatchers.IO) { api.aerial(wla, wlo, 400, 240) }
                        if (b != null) runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()?.let { werfBmps[w.name] = it }
                    }
                }
            }
            kotlinx.coroutines.delay(15_000)
        }
    }

    val name = (ov?.name?.takeIf { it.isNotBlank() } ?: machineName.takeIf { it.isNotBlank() }) ?: "Kraan"
    val gs = ov?.guidance?.takeIf { it.isNotBlank() } ?: "—"
    val (gsFg, gsBg) = gsColors(gs)
    val werven = ov?.werven ?: emptyList()

    Surface(color = DBg) {
        Row(Modifier.fillMaxSize().statusBarsPadding()) {
            // ── SIDEBAR ──
            Column(Modifier.width(212.dp).fillMaxHeight().background(Color.White).padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.padding(start = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.mv3d_logo), null, Modifier.size(34.dp))
                    Column { Text("MV3D", color = DRed, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text("Machineapp", color = DMuted, fontSize = 11.sp) }
                }
                NavItem(Icons.Outlined.Agriculture, "Mijn kraan", true) {}
                NavItem(Icons.Outlined.Foundation, "Werven", false) {}
                NavItem(Icons.Outlined.Map, "Kaart", false) {}
                NavItem(Icons.Outlined.Folder, "Bestanden", false) {}
                NavItem(Icons.Outlined.SwapHoriz, "Convertor", false) { onConvert("Unicontrol") }
                NavItem(Icons.Outlined.ScreenShare, "Scherm delen", false) { onSettings() }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DPanel2).padding(12.dp)) {
                    Text("Ingelogd als kraan", color = DMuted, fontSize = 11.sp)
                    Text(name, color = DInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(DOn)); Text("Online · $gs", color = DOn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                NavItem(Icons.Outlined.Settings, "Instellingen", false) { onSettings() }
                NavItem(Icons.Outlined.PowerSettingsNew, "Uitloggen", false) { onLogout() }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(DLine))

            // ── MAIN ──
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("MIJN KRAAN", color = DMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    // machinekaart
                    Card(Modifier.width(320.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, DLine)) {
                        Column(Modifier.padding(22.dp)) {
                            Box(Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)).background(DRedTint), contentAlignment = Alignment.Center) {
                                Image(painterResource(R.drawable.ic_excavator), null, Modifier.size(58.dp), colorFilter = ColorFilter.tint(DRed))
                            }
                            Text(name, color = DInk, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(gsBg).padding(horizontal = 9.dp, vertical = 3.dp)) { Text(gs, color = gsFg, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                Row(Modifier.clip(RoundedCornerShape(50)).background(DOnBg).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(DOn)); Text("Online", color = DOn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Fact("Kraancode", code, mono = true)
                            Fact("Actieve werf", werven.firstOrNull()?.name ?: "—")
                            Fact("Werven", werven.size.toString())
                        }
                    }
                    // kaart
                    Card(Modifier.weight(1f).height(300.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, DLine)) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.LocationOn, null, tint = DGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
                                Text("Waar staat de kraan", color = DInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(ov?.let { if (it.lat != null && it.lon != null) "%.4f, %.4f".format(it.lat, it.lon) else "" } ?: "", color = DMuted, fontSize = 12.sp)
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                val mb = mapBmp
                                if (mb != null) Image(mb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Box(Modifier.fillMaxSize().background(DPanel2), contentAlignment = Alignment.Center) { Text("Kaart laden…", color = DMuted, fontSize = 13.sp) }
                                // groen bolletje
                                Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(Color.White), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(DGreen))
                                }
                            }
                        }
                    }
                }

                // ── WERVEN ──
                if (werven.isNotEmpty()) {
                    Text("WERVEN", color = DMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        werven.take(3).forEachIndexed { i, w ->
                            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, DLine)) {
                                Column {
                                    Box(Modifier.fillMaxWidth().height(108.dp), contentAlignment = Alignment.Center) {
                                        val wb = werfBmps[w.name]
                                        if (wb != null) Image(wb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        else Box(Modifier.fillMaxSize().background(DPanel2))
                                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(Color.White), contentAlignment = Alignment.Center) { Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(DGreen)) }
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
                        repeat(3 - werven.take(3).size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                // ── CONVERTOR ──
                Text("BESTANDSCONVERTOR", color = DMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                val tiles = listOf("Unicontrol" to R.drawable.tile_unicontrol, "Topcon" to R.drawable.tile_topcon, "Leica" to R.drawable.tile_leica, "Trimble" to R.drawable.tile_trimble, "CHCNAV" to R.drawable.tile_chcnav)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((brand, res) in tiles) {
                        Image(painterResource(res), brand, Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable { onConvert(brand) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
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

@Composable
private fun NavItem(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) DRedTint else Color.Transparent).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, tint = if (on) DRed else DSoft, modifier = Modifier.size(19.dp))
        Text(label, color = if (on) DRed else DSoft, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
