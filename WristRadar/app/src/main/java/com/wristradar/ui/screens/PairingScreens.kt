package com.wristradar.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.ble.DistanceEstimator
import com.wristradar.core.NearbyUser
import com.wristradar.core.PairingState
import com.wristradar.ui.components.Avatar
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.RadarSweep
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.SignalBars
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.animationsOn
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType
import kotlin.math.PI

/** Keeps this watch discoverable while a discovery / pairing screen is on screen. */
@Composable
private fun HoldDiscovery() {
    val engine = WristRadarApp.graph.engine
    DisposableEffect(Unit) {
        engine.acquireDiscovery()
        onDispose { engine.releaseDiscovery() }
    }
}

@Composable
fun DiscoveryScreen(nav: NavHostController) {
    HoldDiscovery()
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val nearby by g.engine.nearby.collectAsState()
    val partner by g.trust.partner.collectAsState()
    val partnerName by g.profile.partnerName.collectAsState()
    val unit = g.settings.state.collectAsState().value.unit

    WrScreen {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RadarSweep(
                    104.dp,
                    blips = nearby.map { u ->
                        val ang = ((u.discoveryId.toLong() and 0xffff) / 65535f) * 2f * PI.toFloat()
                        ang to ((u.distanceM ?: 5.0) / 15.0).coerceIn(0.2, 0.95).toFloat()
                    },
                )
            }
        }
        item { SectionLabel("Nearby WristRadar users") }
        if (partner != null) item {
            Hint("You're paired with ${partnerName ?: "a partner"}. Unpair in Settings before pairing someone new.")
        }
        if (nearby.isEmpty()) item {
            Hint("Scanning… Ask the other person to open WristRadar → Connect to nearby watches.")
        }
        items(nearby, key = { it.discoveryId }) { u ->
            WrChip(
                label = u.name ?: "Identifying…",
                secondary = "${DistanceEstimator.format(u.distanceM, unit)} away · ${DistanceEstimator.signalLabel(u.rssi)}",
                icon = { Avatar(u.avatar, u.name, 30.dp, ring = false) },
                onClick = { nav.navigate("pair/${u.discoveryId}") },
            )
        }
        item { Hint("Distances are rough Bluetooth estimates.", Modifier.alpha(0.8f)) }
    }
}

@Composable
fun PairTargetScreen(nav: NavHostController, discoveryId: Int) {
    HoldDiscovery()
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val nearby by g.engine.nearby.collectAsState()
    val pairing by g.engine.pairing.state.collectAsState()
    val unit = g.settings.state.collectAsState().value.unit
    var last by remember { mutableStateOf<NearbyUser?>(null) }
    val found = nearby.firstOrNull { it.discoveryId == discoveryId }
    LaunchedEffect(found) { if (found != null) last = found }
    val u = found ?: last

    if (pairing !is PairingState.Idle) { PairingFlowContent(nav, pairing); return }

    WrScreen {
        item { Avatar(u?.avatar, u?.name, 64.dp) }
        item { Text(u?.name ?: "Unknown watch", fontSize = 16.sp, color = p.text, textAlign = TextAlign.Center) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (u == null) "Out of range" else "${DistanceEstimator.format(u.distanceM, unit)} · ${u.rssi} dBm",
                    fontSize = 12.sp, color = p.textDim,
                )
                Spacer(Modifier.width(6.dp))
                SignalBars(u?.rssi)
            }
        }
        item {
            WrChip("Pair with this watch", { g.engine.requestPair(discoveryId) }, primary = true, enabled = u != null)
        }
        item { Hint("Both people press pair, stand together, then compare the 6-digit code.") }
    }
}

/** Route used when the other watch starts pairing with us. */
@Composable
fun PairingFlowScreen(nav: NavHostController) {
    HoldDiscovery()
    val pairing by WristRadarApp.graph.engine.pairing.state.collectAsState()
    LaunchedEffect(pairing) { if (pairing is PairingState.Idle) nav.popBackStack() }
    PairingFlowContent(nav, pairing)
}

@Composable
fun PairingFlowContent(nav: NavHostController, state: PairingState) {
    val engine = WristRadarApp.graph.engine
    val p = LocalPalette.current
    WrScreen {
        when (state) {
            is PairingState.Idle -> item { Hint("…") }
            is PairingState.Connecting -> {
                item { RadarSweep(70.dp) }
                item { SectionLabel("Connecting") }
                item { Hint("Opening a link to ${state.name}…") }
                item { WrChip("Cancel", { engine.pairCancel() }) }
            }
            is PairingState.WaitingForPeer -> {
                item { CircularProgressIndicator(Modifier.size(36.dp), indicatorColor = p.primary) }
                item { SectionLabel("Pairing") }
                item { Hint(state.note) }
                item { WrChip("Cancel", { engine.pairCancel() }) }
            }
            is PairingState.Incoming -> {
                item { SectionLabel("Pair request") }
                item { Text(state.name, fontSize = 18.sp, color = p.text) }
                item { Hint("${state.name} wants to pair with this watch. Only accept if they are with you.") }
                item { WrChip("Accept", { engine.pairAccept() }, primary = true, icon = { Icon(Icons.Filled.Check, null) }) }
                item { WrChip("Decline", { engine.pairDecline() }, danger = true, icon = { Icon(Icons.Filled.Close, null) }) }
            }
            is PairingState.Verify -> {
                item { SectionLabel("Verification code") }
                item { CodeReveal(state.code) }
                item { Hint("Check that ${state.name}'s watch shows exactly the same code.") }
                if (!state.myConfirmed) {
                    item { WrChip("Codes match", { engine.pairConfirm(true) }, primary = true, icon = { Icon(Icons.Filled.Check, null) }) }
                    item { WrChip("Codes differ", { engine.pairConfirm(false) }, danger = true, icon = { Icon(Icons.Filled.Close, null) }) }
                } else {
                    item { CircularProgressIndicator(Modifier.size(28.dp), indicatorColor = p.primary) }
                    item { Hint(if (state.peerConfirmed) "Finishing…" else "Waiting for ${state.name} to confirm…") }
                }
                if (state.peerConfirmed && !state.myConfirmed) item { Hint("${state.name} confirmed ✓") }
            }
            is PairingState.Success -> {
                item { Icon(Icons.Filled.CheckCircle, null, tint = p.connection, modifier = Modifier.size(44.dp)) }
                item { SectionLabel("Paired") }
                item { Text("You and ${state.name} are now a trusted pair.", fontSize = 13.sp, color = p.text, textAlign = TextAlign.Center) }
                item {
                    WrChip("Open chat", {
                        engine.pairDismiss()
                        nav.navigate("chat") { popUpTo("home") }
                    }, primary = true)
                }
            }
            is PairingState.Failed -> {
                item { Icon(Icons.Filled.Warning, null, tint = p.danger, modifier = Modifier.size(36.dp)) }
                item { SectionLabel("Pairing failed") }
                item { Text(state.reason, fontSize = 12.sp, color = p.text, textAlign = TextAlign.Center) }
                item { WrChip("Back", { engine.pairDismiss(); nav.popBackStack() }) }
            }
        }
    }
}

@Composable
private fun CodeReveal(code: String) {
    val p = LocalPalette.current
    val on = animationsOn()
    val anim = remember { Animatable(if (on) 0f else 1f) }
    LaunchedEffect(code) { anim.animateTo(1f, tween(500)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            code,
            style = WrType.code.copy(shadow = Shadow(p.primary, Offset.Zero, 18f)),
            color = p.text,
            modifier = Modifier.alpha(anim.value).scale(0.8f + 0.2f * anim.value),
        )
    }
}
