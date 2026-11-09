package com.wristradar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.audio.CallState
import com.wristradar.core.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class IslandContent(val dot: Color?, val text: String)

/**
 * iPhone-style Dynamic Island: a black pill at the top of the screen that springs open for
 * calls, typing and connect / disconnect, then tucks itself away. Never takes taps.
 */
@Composable
fun DynamicIsland() {
    val g = WristRadarApp.graph
    val call by g.engine.calls.state.collectAsState()
    val presence by g.engine.presence.collectAsState()
    // Only "connected or not" - not every signal update (this overlay sits above every screen).
    val connected by remember { g.engine.connection.map { it.phase == Phase.CONNECTED }.distinctUntilChanged() }
        .collectAsState(g.engine.connection.value.phase == Phase.CONNECTED)

    // Short "Connected" / "Out of range" flash when the connection changes.
    var flash by remember { mutableStateOf<IslandContent?>(null) }
    var lastConnected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(connected) {
        val before = lastConnected
        lastConnected = connected
        if (before == null || before == connected) return@LaunchedEffect
        flash = if (connected) IslandContent(Color(0xFF34C759), "Connected") else IslandContent(Color(0xFFFF9F0A), "Friend out of range")
        delay(2_800)
        flash = null
    }

    val callSecs by produceState(0L, call) {
        val c = call
        while (c is CallState.Active) { value = (System.currentTimeMillis() - c.since) / 1000; delay(1_000) }
    }

    val content: IslandContent? = when {
        call is CallState.Active -> IslandContent(Color(0xFF34C759), "📞 %d:%02d".format(callSecs / 60, callSecs % 60))
        call is CallState.Incoming -> IslandContent(Color(0xFF34C759), "📞 Incoming call")
        call is CallState.Outgoing -> IslandContent(Color(0xFF34C759), "📞 Calling…")
        presence.partnerTyping -> IslandContent(null, "✏️ typing…")
        else -> flash
    }

    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = content != null,
            enter = fadeIn() + expandHorizontally(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.CenterHorizontally),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.CenterHorizontally),
        ) {
            val c = content ?: IslandContent(null, "")
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.Black)
                    .animateContentSize(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                c.dot?.let { Box(Modifier.size(6.dp).clip(CircleShape).background(it)); Spacer(Modifier.width(5.dp)) }
                Text(c.text, fontSize = 10.sp, color = Color.White, maxLines = 1)
            }
        }
    }
}
