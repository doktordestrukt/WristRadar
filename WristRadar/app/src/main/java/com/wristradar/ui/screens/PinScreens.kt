package com.wristradar.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.data.AppLock
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------------------ PIN pad

/** Round-screen PIN pad: dots on top, 3x4 keys, ⌫ and ✓. Calls [onDone] with 4-6 digits. */
@Composable
fun PinPad(title: String, subtitle: String?, error: String?, busy: Boolean, onDone: (String) -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    var pin by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(error) {
        if (error != null) {
            pin = ""
            for (x in listOf(10f, -10f, 7f, -7f, 0f)) shake.animateTo(x, tween(45))
        }
    }
    Column(Modifier.fillMaxSize().background(p.background).padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 12.sp, color = p.text, fontWeight = FontWeight.Bold)
        Text(error ?: subtitle ?: " ", fontSize = 9.sp, color = if (error != null) p.danger else p.textDim, textAlign = TextAlign.Center)
        Row(Modifier.padding(vertical = 4.dp).graphicsLayer { translationX = shake.value }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(6) { i ->
                val filled = i < pin.length
                if (i < maxOf(4, pin.length)) Box(
                    Modifier.size(8.dp).clip(CircleShape).background(if (filled) p.primary else p.textDim.copy(alpha = 0.35f))
                )
            }
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                row.forEach { k ->
                    val enabled = !busy && when (k) { "✓" -> AppLock.valid(pin); "⌫" -> pin.isNotEmpty(); else -> pin.length < 6 }
                    Box(
                        Modifier.size(width = 46.dp, height = 30.dp).clip(RoundedCornerShape(50))
                            .background(if (k == "✓" && enabled) p.primary else Color.White.copy(alpha = if (enabled) 0.14f else 0.05f))
                            .clickable(enabled = enabled) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                when (k) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "✓" -> onDone(pin)
                                    else -> pin += k
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(k, fontSize = 15.sp, color = if (k == "✓" && enabled) p.onColor(p.primary) else p.text.copy(alpha = if (enabled) 1f else 0.4f))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ lock overlay

/** Covers every screen while the app is locked. Calls continue underneath. */
@Composable
fun LockOverlay() {
    val g = WristRadarApp.graph
    val locked by g.lock.locked.collectAsState()
    if (!locked) return
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var wait by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { wait = ((g.lock.waitMs() + 999) / 1000).toInt(); delay(500) }
    }
    Box(Modifier.fillMaxSize().clickable(enabled = false) {}) {
        PinPad(
            title = "🔐 WristRadar locked",
            subtitle = if (wait > 0) "Too many tries - wait ${wait}s" else "Enter your PIN",
            error = error, busy = busy || wait > 0,
        ) { pin ->
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.Default) { g.lock.verify(pin) }
                busy = false
                error = if (ok) null else "Wrong PIN"
            }
        }
    }
}

// ------------------------------------------------------------------------------ set up / change

@Composable
fun PinSetupScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(if (g.lock.isSet) 0 else 1) }   // 0 current, 1 new, 2 confirm
    var first by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val title = when (step) { 0 -> "Current PIN"; 1 -> "New PIN"; else -> "Confirm PIN" }
    PinPad(title, if (step == 1) "4 to 6 digits" else null, error, busy) { pin ->
        when (step) {
            0 -> { busy = true; scope.launch { val ok = withContext(Dispatchers.Default) { g.lock.check(pin) }; busy = false; if (ok) { error = null; step = 1 } else error = "Wrong PIN" } }
            1 -> { first = pin; error = null; step = 2 }
            else -> if (pin != first) { error = "Didn't match - try again"; step = 1 } else {
                busy = true
                scope.launch {
                    withContext(Dispatchers.Default) { g.lock.setPin(pin) }
                    busy = false
                    nav.popBackStack()
                }
            }
        }
    }
}

@Composable
fun PinSettingsScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    var tick by remember { mutableIntStateOf(0) }   // refresh after changes
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (removing) {
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        PinPad("Turn off PIN", "Enter your current PIN", error, busy) { pin ->
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.Default) { g.lock.check(pin) }
                busy = false
                if (ok) { g.lock.remove(); removing = false; tick++ } else error = "Wrong PIN"
            }
        }
        return
    }
    WrScreen {
        item { SectionLabel("App lock PIN") }
        if (!g.lock.isSet) {
            item { Hint("Lock WristRadar with a PIN so nobody can read your chats if they pick up your watch.") }
            item { WrChip("Set up a PIN", { nav.navigate("pinsetup") }, primary = true, tile = false) }
        } else {
            item(key = "every$tick") {
                val opts = listOf(0 to "Every time you open it", 5 to "After 5 minutes", 30 to "Every 30 minutes", 60 to "Every hour")
                val cur = g.lock.relockMinutes
                WrChip("Ask for PIN", {
                    val i = opts.indexOfFirst { it.first == cur }
                    g.lock.relockMinutes = opts[(i + 1) % opts.size].first; tick++
                }, secondary = (opts.firstOrNull { it.first == cur }?.second ?: "Every $cur minutes") + " (tap to change)", tile = false)
            }
            item { WrChip("Change PIN", { nav.navigate("pinsetup") }, tile = false) }
            item { WrChip("Lock now", { g.lock.lockNow() }, tile = false) }
            item { WrChip("Turn off PIN", { removing = true }, danger = true, tile = false) }
            item { Hint("While the PIN is on, notifications say \"New message\" without showing what it says.") }
        }
    }
}

// ------------------------------------------------------------------------------ "We've launched pins!"

/** One-time announcement right after the What's New screen, with everything floating up into place. */
@Composable
fun PinIntroScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val lockY = remember { Animatable(140f) }
    val glow = remember { Animatable(0f) }
    val texts = List(4) { remember { Animatable(0f) } }
    LaunchedEffect(Unit) {
        launch { lockY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        launch { delay(250); glow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        texts.forEachIndexed { i, a -> launch { delay(350L + i * 140L); a.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) } }
    }
    fun Modifier.rise(a: Animatable<Float, *>) = graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 60f }
    fun done(setUp: Boolean) {
        g.settings.update { it.copy(pinIntroSeen = true) }
        nav.popBackStack()
        if (setUp) nav.navigate("pinsetup")
    }
    Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
        // soft halo that blooms behind the lock as it lands
        Box(
            Modifier.size(150.dp).graphicsLayer { alpha = glow.value * 0.55f; scaleX = 0.6f + glow.value * 0.5f; scaleY = scaleX }
                .clip(CircleShape).background(p.primary.copy(alpha = 0.35f))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 18.dp)) {
            Text("🔐", fontSize = 44.sp, modifier = Modifier.graphicsLayer { translationY = lockY.value; rotationZ = lockY.value / 10f })
            Spacer(Modifier.height(4.dp))
            Text("We've launched pins!", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.text, textAlign = TextAlign.Center, modifier = Modifier.rise(texts[0]))
            Text("Lock WristRadar with a PIN so nobody can read your chats.", fontSize = 10.sp, color = p.textDim, textAlign = TextAlign.Center, modifier = Modifier.rise(texts[1]))
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.rise(texts[2]).fillMaxWidth().clip(RoundedCornerShape(50)).background(p.primary).clickable { done(true) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Set up my PIN", fontSize = 13.sp, color = p.onColor(p.primary), fontWeight = FontWeight.Bold) }
            Text("Not now", fontSize = 11.sp, color = p.textDim, modifier = Modifier.rise(texts[3]).clickable { done(false) }.padding(8.dp))
        }
    }
}
