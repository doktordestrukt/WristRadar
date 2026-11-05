package com.wristradar.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.wristradar.BuildConfig
import com.wristradar.WristRadarApp
import com.wristradar.core.Phase
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType
import kotlinx.coroutines.delay

// --------------------------------------------------------------------------------- updates

@Composable
fun UpdatesScreen() {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val st by g.engine.updater.state.collectAsState()
    val s by g.settings.state.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    WrScreen {
        item { SectionLabel("Updates") }
        item { Text("This watch: ${BuildConfig.RELEASE_LABEL} (${BuildConfig.VERSION_NAME})", fontSize = 12.sp, color = p.text) }
        item {
            val pa = st.partner
            Text(
                when {
                    pa == null -> "Friend: not connected"
                    !pa.isWatch -> "Friend: PC app ${pa.label}"
                    else -> "Friend's watch: ${pa.label}" + if (pa.versionCode < BuildConfig.VERSION_CODE) " (older)" else ""
                },
                fontSize = 12.sp, color = if (pa != null && pa.versionCode < BuildConfig.VERSION_CODE) p.warning else p.textDim,
            )
        }
        st.readyLabel?.let { label ->
            item { WrChip("Install $label now", { msg = g.engine.updater.install() }, secondary = "Your chats and pairing are kept", primary = true) }
        }
        st.sendingPct?.let { pct -> item { Progress("Sending update to friend", pct) } }
        st.receivingPct?.let { pct -> item { Progress("Receiving ${st.receivingLabel ?: "update"}", pct) } }
        (msg ?: st.note)?.let { item { Hint(it) } }

        item { SectionLabel("From your friend (Bluetooth)") }
        item { WrChip("Send update to friend", { msg = g.engine.updater.sendToPartner() }, secondary = "Gives them your version, offline") }
        item { WristToggle("Auto-send to friend", s.autoSendUpdates, "When they come back in range") { v -> g.settings.update { it.copy(autoSendUpdates = v) } } }

        item { SectionLabel("From GitHub (Wi-Fi)") }
        item { WrChip(if (st.githubBusy) "Checking…" else "Check now", { if (!st.githubBusy) g.engine.updater.checkNow() }, secondary = st.github) }
        item { WristToggle("Check GitHub automatically", s.githubUpdates, "Every few hours, Wi-Fi only") { v -> g.settings.update { it.copy(githubUpdates = v) } } }

        item { SectionLabel("Installing") }
        item { WristToggle("Install automatically", s.autoInstallUpdates, "Waits until calls and transfers finish") { v -> g.settings.update { it.copy(autoInstallUpdates = v) } } }
        item {
            Hint("Only updates signed with your key are accepted. The first update may ask you to tap Install; after that it can update by itself. Messages never go online.")
        }
    }
}

@Composable
private fun WristToggle(label: String, checked: Boolean, secondary: String, onChange: (Boolean) -> Unit) =
    WrToggle(label, checked, secondary, onChange)

@Composable
private fun Progress(label: String, pct: Int) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text("$label · $pct%", fontSize = 12.sp, color = p.text)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.textDim.copy(alpha = 0.3f))) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(pct / 100f).height(4.dp).background(p.primary))
        }
    }
}

// --------------------------------------------------------------------------------- walk

/** How far your friend is, as a walk: steps, metres/feet, km/miles and time at your pace. */
@Composable
fun WalkScreen() {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val conn by g.engine.connection.collectAsState()
    val s by g.settings.state.collectAsState()
    var granted by remember { mutableStateOf(g.engine.walk.hasPermission) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; if (ok) g.engine.walk.start()
    }
    // Hot / cold: compare the signal now with a few seconds ago.
    var prevRssi by remember { mutableStateOf<Int?>(null) }
    var trend by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val r = g.engine.connection.value.rssi
            val old = prevRssi
            trend = if (r == null || old == null) 0 else if (r - old >= 3) 1 else if (old - r >= 3) -1 else 0
            prevRssi = r
            delay(4_000)
        }
    }
    val metres = conn.distanceM?.takeIf { conn.phase == Phase.CONNECTED }
    WrScreen {
        item { SectionLabel("Walk to ${g.profile.partnerName.value ?: "friend"}") }
        if (metres == null) {
            item { Hint("Connect to your friend to see how far away they are.") }
        } else {
            val e = g.engine.walk.estimate(metres)
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${e.steps}", style = WrType.code, color = p.primary)
                    Text("steps", style = WrType.label, color = p.textDim)
                    Text(timeText(e.seconds) + " walk", fontSize = 13.sp, color = p.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (trend) { 1 -> "🔥 Getting closer"; -1 -> "🧊 Getting further"; else -> "Hold still to settle" },
                        fontSize = 11.sp, color = when (trend) { 1 -> p.connection; -1 -> p.warning; else -> p.textDim },
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Stat("Metres", "%.0f m".format(e.metres))
                    Stat("Feet", "%.0f ft".format(e.feet))
                    Stat("Yards", "%.0f yd".format(e.yards))
                    Stat("Kilometres", "%.3f km".format(e.km))
                    Stat("Miles", "%.3f mi".format(e.miles))
                    Stat("Your step", "%.2f m".format(e.strideM))
                    Stat("Your pace", "%.1f km/h".format(e.speedMs * 3.6) + if (e.learned) "" else " (default)")
                }
            }
            item { Hint("Distance comes from Bluetooth signal strength, so it's an estimate. Calibrate in Settings › Connection for better numbers.") }
        }
        item { SectionLabel("Your height") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Button({ g.settings.update { it.copy(heightCm = (it.heightCm - 2).coerceAtLeast(100)) } }, Modifier.padding(4.dp),
                    colors = ButtonDefaults.secondaryButtonColors()) { Text("−") }
                Text("${s.heightCm} cm", fontSize = 15.sp, color = p.text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                Button({ g.settings.update { it.copy(heightCm = (it.heightCm + 2).coerceAtMost(230)) } }, Modifier.padding(4.dp),
                    colors = ButtonDefaults.secondaryButtonColors()) { Text("+") }
            }
        }
        if (!granted) item {
            WrChip("Learn my walking pace", { launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                secondary = "Uses the step sensor to time your walk", primary = true)
        } else item {
            Hint(if (s.walkSpeed > 0f) "Pace learned from your walks." else "Walk normally for a bit and your pace will be learned.")
        }
        if (s.walkSpeed > 0f) item { WrChip("Reset my pace", { g.settings.update { it.copy(walkSpeed = 0f) } }) }
    }
}

@Composable
private fun Stat(k: String, v: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 11.sp, color = p.textDim)
        Text(v, fontSize = 11.sp, color = p.text)
    }
}

private fun timeText(sec: Int): String = when {
    sec < 60 -> "${sec.coerceAtLeast(1)} sec"
    sec < 3600 -> "${sec / 60} min ${sec % 60} sec"
    else -> "${sec / 3600} h ${sec % 3600 / 60} min"
}
