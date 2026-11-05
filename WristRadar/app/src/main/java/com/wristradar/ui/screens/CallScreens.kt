package com.wristradar.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.wristradar.R
import com.wristradar.WristRadarApp
import com.wristradar.audio.CallState
import com.wristradar.audio.VoiceRecorder
import com.wristradar.audio.hasMicPermission
import com.wristradar.ui.components.Avatar
import com.wristradar.ui.components.SwipeSlider
import com.wristradar.ui.components.animationsOn
import com.wristradar.ui.components.produceNow
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Asks for the microphone once (if needed), then runs [action] either way. */
@Composable
private fun rememberWithMic(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pending?.invoke(); pending = null }
    return { action -> if (hasMicPermission(ctx)) action() else { pending = action; launcher.launch(Manifest.permission.RECORD_AUDIO) } }
}

@Composable
private fun PulsingAvatar(ringing: Boolean, compact: Boolean) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val name by g.profile.partnerName.collectAsState()
    val avatar by g.profile.partnerAvatar.collectAsState()
    val t = rememberInfiniteTransition(label = "ring")
    val r by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "r")
    val animate = ringing && animationsOn()
    Box(Modifier.size(if (compact) 48.dp else 66.dp), contentAlignment = Alignment.Center) {
        if (animate) Canvas(Modifier.fillMaxSize()) {
            for (k in 0..1) {
                val f = (r + k * 0.5f) % 1f
                drawCircle(p.primary.copy(alpha = (1f - f) * 0.6f), radius = size.minDimension / 2 * (0.6f + 0.4f * f), style = Stroke(2.dp.toPx()))
            }
        }
        Avatar(avatar, name, if (compact) 42.dp else 46.dp)
    }
}

@Composable
fun CallScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val calls = g.engine.calls
    val st by calls.state.collectAsState()
    val name by g.profile.partnerName.collectAsState()
    val withMic = rememberWithMic()
    val now by produceNow()

    LaunchedEffect(st) {
        when (st) {
            is CallState.Ended -> { delay(1800); calls.dismissResult(); nav.popBackStack() }
            CallState.Idle -> nav.popBackStack()
            else -> Unit
        }
    }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(p.background, p.secondary.copy(alpha = 0.28f), p.background))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PulsingAvatar(ringing = st is CallState.Incoming || st is CallState.Outgoing, compact = st is CallState.Active)
            Text(name ?: "Partner", fontSize = 15.sp, maxLines = 1, fontWeight = FontWeight.SemiBold, color = p.text)
            AnimatedContent(st::class, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "st") { cls ->
                val s = st.takeIf { it::class == cls } ?: st
                Text(
                    when (s) {
                        CallState.Incoming -> "WristRadar call…"
                        CallState.Outgoing -> "Calling…"
                        is CallState.Active -> (if (s.muted) "Muted · " else "") + mmss(now - s.since)
                        CallState.NoAnswer -> "No answer"
                        is CallState.Ended -> s.reason + if (s.durationMs > 0) " · " + mmss(s.durationMs) else ""
                        CallState.Idle -> ""
                    },
                    style = WrType.label.copy(fontSize = 11.sp), color = if (s is CallState.Active) p.connection else p.textDim,
                )
            }
            Spacer(Modifier.height(6.dp))
            when (val s = st) {
                CallState.Incoming -> {
                    SwipeSlider("slide to answer", p.connection, Icons.Filled.Call) { withMic { calls.accept() } }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { calls.decline() }, modifier = Modifier.size(32.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = p.danger)) {
                        Icon(Icons.Filled.Call, "Decline", modifier = Modifier.rotate(135f))
                    }
                }
                CallState.Outgoing -> EndButton { calls.hangUp() }
                is CallState.Active -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { calls.toggleMute() }, modifier = Modifier.size(40.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = if (s.muted) p.text else p.surface, contentColor = if (s.muted) p.background else p.text)) {
                            Icon(painterResource(R.drawable.ic_mic), if (s.muted) "Unmute" else "Mute")
                        }
                        EndButton { calls.hangUp() }
                        // Volume boost: tap to cycle 1x -> 1.6x -> 2.2x -> 3x (makes your friend louder).
                        val boost = g.settings.state.collectAsState().value.callBoost
                        Button(onClick = {
                            val steps = listOf(1f, 1.6f, 2.2f, 3f)
                            calls.setBoost(steps.firstOrNull { it > boost + 0.05f } ?: steps.first())
                        }, modifier = Modifier.size(40.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = if (boost > 1.05f) p.primary else p.surface, contentColor = if (boost > 1.05f) p.onColor(p.primary) else p.text)) {
                            Text("🔊" + (if (boost > 1.05f) "×" + "%.1f".format(boost).trimEnd('0').trimEnd('.') else ""), fontSize = 10.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    SwipeSlider("slide to hang up", p.danger, Icons.Filled.Call) { calls.hangUp() }
                }
                CallState.NoAnswer -> {
                    Button(onClick = { calls.dismissResult(); nav.navigate("voicenote/voicemail") { popUpTo("call") { inclusive = true } } },
                        modifier = Modifier.fillMaxWidth(0.8f).height(40.dp), colors = ButtonDefaults.buttonColors(backgroundColor = p.primary)) {
                        Text("Leave a voicemail", color = p.onColor(p.primary), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { calls.dismissResult(); nav.popBackStack() }, modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                        colors = ButtonDefaults.buttonColors(backgroundColor = p.surface)) { Icon(Icons.Filled.Close, "Close") }
                }
                else -> Unit
            }
        }
    }
}

/** Record a voicemail / voice note (max 30 s) and send it. */
@Composable
fun VoiceNoteScreen(nav: NavHostController, mode: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec = remember { VoiceRecorder() }
    val elapsed by rec.elapsedMs.collectAsState()
    val level by rec.level.collectAsState()
    var recording by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val withMic = rememberWithMic()
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")

    LaunchedEffect(Unit) {
        withMic {
            if (hasMicPermission(ctx)) recording = rec.start { recording = false }
            else note = "Microphone permission is needed."
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (recording) rec.cancel() } }

    fun send() {
        val bytes = rec.stop(); recording = false
        if (bytes == null) { note = "Too short - hold on a bit longer."; return }
        scope.launch { g.engine.messenger.sendVoice(bytes); nav.popBackStack() }
    }

    Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(if (mode == "voicemail") "VOICEMAIL" else "VOICE MESSAGE", style = WrType.label, color = p.primary)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(p.danger.copy(alpha = if (recording) blink else 0.3f)),
                contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_mic), null, tint = androidx.compose.ui.graphics.Color.White) }
            Spacer(Modifier.height(6.dp))
            Text("${mmss(elapsed)} / ${mmss(VoiceRecorder.MAX_MS.toLong())}", fontSize = 14.sp, color = p.text)
            Box(Modifier.fillMaxWidth(0.7f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.surface)) {
                Box(Modifier.fillMaxWidth(level.coerceIn(0.02f, 1f)).height(5.dp).background(p.primary))
            }
            if (note.isNotEmpty()) Text(note, fontSize = 11.sp, color = p.danger, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { rec.cancel(); recording = false; nav.popBackStack() }, modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                    colors = ButtonDefaults.buttonColors(backgroundColor = p.surface)) { Icon(Icons.Filled.Close, "Cancel") }
                Button(onClick = { send() }, modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                    colors = ButtonDefaults.buttonColors(backgroundColor = p.primary)) { Icon(Icons.Filled.Send, "Send") }
            }
        }
    }
}

/** Big red tap-to-end button - always reachable, no swipe needed. */
@Composable
private fun EndButton(onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(
        onClick = onClick, modifier = Modifier.size(54.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = p.danger, contentColor = androidx.compose.ui.graphics.Color.White),
    ) { Icon(Icons.Filled.Call, "Hang up", modifier = Modifier.size(26.dp).rotate(135f)) }
}
