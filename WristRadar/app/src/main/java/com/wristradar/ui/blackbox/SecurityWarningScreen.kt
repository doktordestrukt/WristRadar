package com.wristradar.ui.blackbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.ui.components.KeyValue
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.relativeTime
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType

/**
 * Themed warning shown to BOTH legitimate users when an intrusion attempt is detected,
 * with plain instructions. (The skull screen is reserved for the intruder's device.)
 */
@Composable
fun SecurityWarningOverlay(onOpenLog: () -> Unit) {
    val g = WristRadarApp.graph
    val w by g.blackbox.warning.collectAsState()
    val warn = w ?: return
    val p = LocalPalette.current
    val pulse by rememberInfiniteTransition(label = "warn").animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    BackHandler { g.blackbox.dismissWarning() }
    Box(
        Modifier.fillMaxSize().background(p.background)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        WrScreen {
            item { Icon(Icons.Filled.Warning, null, tint = p.danger, modifier = Modifier.size(38.dp).scale(pulse)) }
            item { Text("SECURITY WARNING", style = WrType.title.copy(fontSize = 13.sp), color = p.danger) }
            item {
                Text(
                    if (warn.fromPartner) "Your partner's watch blocked someone trying to break into your private link."
                    else "Someone tried to break into your private link. They were blocked.",
                    fontSize = 13.sp, color = p.text, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium,
                )
            }
            item { KeyValue("What happened", warn.type.title + if (warn.fromPartner) "" else "\n" + warn.detail) }
            if (!warn.fromPartner) item { KeyValue("Device", warn.deviceName) }
            item { KeyValue("When", relativeTime(warn.time) + if (warn.repeats > 0) " · ${warn.repeats + 1} attempts" else "") }
            item { KeyValue("Your messages", "Safe. Nothing was decrypted - the attempt failed its security check.") }
            item { Text("WHAT TO DO", style = WrType.label, color = p.primary, modifier = Modifier.padding(top = 4.dp)) }
            item {
                Text(
                    "1. Don't send anything sensitive for now.\n" +
                        "2. Check your partner's name and fingerprint in their profile.\n" +
                        "3. If it happens again, move somewhere else or turn Bluetooth off for a minute.\n" +
                        "4. Still worried? Unpair, then pair again face to face and compare the code carefully.",
                    fontSize = 12.sp, color = p.text, modifier = Modifier.fillMaxWidth(),
                )
            }
            item { WrChip("I understand", { g.blackbox.dismissWarning() }, primary = true, icon = { Icon(Icons.Filled.Check, null) }) }
            item { WrChip("Security log", { g.blackbox.dismissWarning(); onOpenLog() }, icon = { Icon(Icons.Filled.Lock, null) }) }
        }
    }
}
