package be.mv3d.tablet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Licht + 3DG-rood
private val LBg = Color(0xFFFAFBFC); private val LPanel = Color(0xFFFFFFFF)
private val LSurface = Color(0xFFF2F3F7); private val LInk = Color(0xFF1A1D26)
private val LSoft = Color(0xFF5A6275); private val LMuted = Color(0xFF8891A5); private val LLine = Color(0xFFDCDFE7)
private val Red = Color(0xFFE30613); private val Red2 = Color(0xFFFF3A47)

private data class Lg(
    val top: String, val subtitle: String, val chip: String, val heading: String, val lead: String,
    val segEmail: String, val segCode: String, val email: String, val pw: String, val codeLabel: String,
    val btn: String, val foot: String, val emailPh: String, val pwPh: String, val codePh: String, val err: String,
)
private val I18N = mapOf(
    "nl" to Lg("MV3D · Machinebeheer", "Machineomgeving", "Kraantablet", "Aanmelden",
        "Meld je aan met je MV3D-account om je werven en machines te beheren.",
        "E-mail & wachtwoord", "Toegangscode", "E-mail", "Wachtwoord", "Toegangscode", "Inloggen",
        "MV3D-Manager · machineomgeving", "naam@bedrijf.be", "Je wachtwoord", "BV. K7F3A92X",
        "Aanmelden mislukt — controleer je gegevens."),
    "fr" to Lg("MV3D · Gestion des machines", "Environnement machine", "Tablette grue", "Se connecter",
        "Connectez-vous avec votre compte MV3D pour gérer vos chantiers et machines.",
        "E-mail & mot de passe", "Code d'accès", "E-mail", "Mot de passe", "Code d'accès", "Connexion",
        "MV3D-Manager · environnement machine", "nom@entreprise.be", "Votre mot de passe", "EX. K7F3A92X",
        "Échec de connexion — vérifiez vos informations."),
    "en" to Lg("MV3D · Machine management", "Machine environment", "Crane tablet", "Sign in",
        "Sign in with your MV3D account to manage your sites and machines.",
        "Email & password", "Access code", "Email", "Password", "Access code", "Sign in",
        "MV3D-Manager · machine environment", "name@company.com", "Your password", "E.G. K7F3A92X",
        "Sign-in failed — check your details."),
)
private fun lg(lang: String) = I18N[lang] ?: I18N["nl"]!!

/** Vertaalde standaard-foutmelding (voor MainActivity). */
fun loginErrorText(lang: String): String = lg(lang).err

@Composable
fun LoginScreen(
    lang: String,
    onLang: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onEmailLogin: (String, String) -> Unit,
    onCodeLogin: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val t = lg(lang)
    var mode by remember { mutableStateOf("email") }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Surface(color = LBg) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 720.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) LeftPanel(t, Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.then(if (wide) Modifier.width(460.dp) else Modifier.fillMaxWidth()).fillMaxHeight().background(LPanel), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 340.dp).padding(horizontal = 40.dp)) {
                        // taalswitcher
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.align(Alignment.End).padding(bottom = 18.dp)) {
                            for (l in listOf("nl", "fr", "en")) {
                                val on = l == lang
                                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Red else LSurface).clickable { onLang(l) }.padding(horizontal = 11.dp, vertical = 6.dp)) {
                                    Text(l.uppercase(), color = if (on) Color.White else LMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Text(t.heading, color = LInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(t.lead, color = LMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))

                        // segment
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LSurface).padding(4.dp)) {
                            for ((id, label) in listOf("email" to t.segEmail, "code" to t.segCode)) {
                                val on = mode == id
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) LPanel else Color.Transparent).clickable { mode = id }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = if (on) LInk else LMuted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))

                        if (mode == "email") {
                            Field(t.email, email, { email = it }, t.emailPh, KeyboardType.Email)
                            Spacer(Modifier.height(14.dp))
                            Field(t.pw, pw, { pw = it }, t.pwPh, KeyboardType.Password, password = true)
                        } else {
                            Field(t.codeLabel, code, { code = it.uppercase() }, t.codePh, KeyboardType.Text, mono = true)
                        }
                        Spacer(Modifier.height(18.dp))

                        // knop (rode gradient)
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Red2, Red)))
                            .clickable(enabled = !busy) { if (mode == "email") onEmailLogin(email, pw) else onCodeLogin(code) }
                            .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text(t.btn, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        if (error != null) Text(error, color = Red, fontSize = 12.5.sp, modifier = Modifier.padding(top = 12.dp))
                        val skipTxt = when (lang) { "fr" -> "Passer pour l'instant"; "en" -> "Skip for now"; else -> "Voorlopig overslaan" }
                        Text(skipTxt, color = LMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp).clip(RoundedCornerShape(6.dp)).clickable { onSkip() }.padding(6.dp))
                        Text(t.foot + " · © 2026 MV3D", color = LMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, ph: String, kb: KeyboardType, password: Boolean = false, mono: Boolean = false) {
    Column {
        Text(label, color = LSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 7.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            placeholder = { Text(ph, color = LMuted, fontSize = 15.sp) },
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            textStyle = androidx.compose.ui.text.TextStyle(color = LInk, fontSize = if (mono) 17.sp else 15.sp, textAlign = if (mono) TextAlign.Center else TextAlign.Start, letterSpacing = if (mono) 4.sp else 0.sp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Red, unfocusedBorderColor = LLine,
                focusedContainerColor = LSurface, unfocusedContainerColor = LSurface,
                cursorColor = Red,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LeftPanel(t: Lg, modifier: Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFFF4F5F8), Color(0xFFECEEF3), Color(0xFFE4E7EE))))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val cx = w * 0.24f; val cy = h * 0.34f
            val red = Red
            drawCircle(red.copy(alpha = 0.20f), radius = w * 0.14f, center = Offset(cx, cy), style = Stroke(2f))
            drawCircle(red.copy(alpha = 0.11f), radius = w * 0.22f, center = Offset(cx, cy), style = Stroke(2f))
            drawCircle(red.copy(alpha = 0.05f), radius = w * 0.31f, center = Offset(cx, cy), style = Stroke(2f))
            drawCircle(Color(0xFF9AA3B2).copy(alpha = 0.18f), radius = w * 0.10f, center = Offset(w * 0.72f, h * 0.72f), style = Stroke(2f))
            // meet-kruisjes
            fun plus(px: Float, py: Float, s: Float) {
                drawLine(red.copy(alpha = 0.5f), Offset(px, py - s), Offset(px, py + s), 2f)
                drawLine(red.copy(alpha = 0.5f), Offset(px - s, py), Offset(px + s, py), 2f)
            }
            plus(w * 0.52f, h * 0.18f, 15f); plus(w * 0.80f, h * 0.34f, 13f); plus(w * 0.40f, h * 0.70f, 13f)
            // vierkantje
            val sq = w * 0.033f
            drawRect(red.copy(alpha = 0.38f), topLeft = Offset(w * 0.33f, h * 0.63f), size = androidx.compose.ui.geometry.Size(sq, sq), style = Stroke(2f))
            // driehoek
            val tp = Path().apply { moveTo(w * 0.70f, h * 0.40f); lineTo(w * 0.77f, h * 0.44f); lineTo(w * 0.70f, h * 0.48f); close() }
            drawPath(tp, red.copy(alpha = 0.11f))
        }
        // logo + label bovenaan
        Row(Modifier.align(Alignment.TopStart).padding(start = 44.dp, top = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Red2, Red))), contentAlignment = Alignment.Center) {
                Text("M", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Text(t.top, color = LSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        // woordmerk onderaan
        Column(Modifier.align(Alignment.BottomStart).padding(start = 52.dp, bottom = 64.dp)) {
            Row {
                Text("MV3D", color = Red, fontSize = 68.sp, fontWeight = FontWeight.Bold)
                Text("-Manager", color = LInk, fontSize = 68.sp, fontWeight = FontWeight.Bold)
            }
            Text(t.subtitle, color = LSoft, fontSize = 20.sp, modifier = Modifier.padding(top = 14.dp))
        }
        Text(t.chip, color = LMuted, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 52.dp, bottom = 34.dp))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Red, Color.Transparent))))
    }
}
