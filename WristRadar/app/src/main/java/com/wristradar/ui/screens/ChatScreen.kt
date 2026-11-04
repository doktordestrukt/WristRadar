package com.wristradar.ui.screens

import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.wristradar.R
import com.wristradar.WristRadarApp
import com.wristradar.ble.DistanceEstimator
import com.wristradar.core.Phase
import com.wristradar.data.ChatMessage
import com.wristradar.data.db.MsgStatus
import com.wristradar.ui.components.Avatar
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SignalBars
import com.wristradar.ui.components.StatusDot
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.animationsOn
import com.wristradar.ui.components.phaseColor
import com.wristradar.ui.components.rememberTextInput
import com.wristradar.ui.components.presenceText
import com.wristradar.ui.components.TypingDots
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType
import kotlinx.coroutines.launch
import java.util.Date

@Composable
fun ChatScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val messages by g.messages.messages.collectAsState(initial = null)
    val conn by g.engine.connection.collectAsState()
    val partnerName by g.profile.partnerName.collectAsState()
    val partnerAvatar by g.profile.partnerAvatar.collectAsState()
    val partner by g.trust.partner.collectAsState()
    val unit = g.settings.state.collectAsState().value.unit
    val openedAt = remember { System.currentTimeMillis() }
    var firstUnreadId by remember { mutableStateOf<String?>(null) }
    var unreadComputed by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    val presence by g.engine.presence.collectAsState()
    val lastSeen by g.trust.partnerLastSeen.collectAsState()
    val sendText = rememberTextInput(onClosed = { g.engine.messenger.sendTyping(false) }) { text -> scope.launch { g.engine.messenger.sendText(text) } }

    DisposableEffect(Unit) {
        g.uiVisibility.chatVisible = true
        g.notifier.cancelMessages()
        onDispose { g.uiVisibility.chatVisible = false }
    }

    val list = messages
    LaunchedEffect(list?.size) {
        if (list == null) return@LaunchedEffect
        if (!unreadComputed) {
            firstUnreadId = list.firstOrNull { !it.outgoing && !it.read }?.id
            unreadComputed = true
        }
        if (list.any { !it.read }) g.messages.markAllRead()
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex > 0) {
            if (animsEnabled) listState.animateScrollToItem(lastIndex) else listState.scrollToItem(lastIndex)
        }
    }

    WrScreen(state = listState) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { nav.navigate("partner") },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Avatar(partnerAvatar, partnerName, 28.dp)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(partnerName ?: "Partner", fontSize = 14.sp, color = p.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(conn.phase, 6.dp)
                        androidx.compose.animation.AnimatedContent(presence.partnerTyping, label = "typing") { typing ->
                            if (typing) Text("typing…", fontSize = 10.sp, color = p.primary)
                            else Text(presenceText(conn.phase == Phase.CONNECTED, lastSeen), fontSize = 10.sp, color = phaseColor(conn.phase))
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { if (g.engine.calls.startCall()) nav.navigate("call") },
                    enabled = conn.phase == Phase.CONNECTED,
                    modifier = Modifier.size(30.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = p.connection.copy(alpha = 0.85f), contentColor = p.background),
                ) { Icon(Icons.Filled.Call, "Call", modifier = Modifier.size(16.dp)) }
            }
        }
        if (partner == null) item { Hint("Not paired. Pair with a watch to chat.") }
        if (list != null && list.isEmpty()) item { Hint("No messages yet. Say hi 👋") }
        list?.forEach { m ->
            if (m.id == firstUnreadId) item(key = "unread-divider") {
                Text("— NEW —", style = WrType.label, color = p.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            item(key = m.id) {
                MessageBubble(
                    m = m,
                    animateIn = m.localTime > openedAt,
                    onImage = { nav.navigate("image/${m.id}") },
                    onRetry = { scope.launch { g.engine.messenger.retry(m.id) } },
                )
            }
        }
        if (presence.partnerTyping) item(key = "typing") {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(p.incoming).padding(horizontal = 12.dp, vertical = 10.dp)) { TypingDots(p.text) }
            }
        }
        item(key = "input") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { g.engine.messenger.sendTyping(true); sendText("Message ${partnerName ?: ""}".trim()) },
                        enabled = partner != null,
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                        colors = ButtonDefaults.primaryButtonColors(backgroundColor = p.primary, contentColor = p.onColor(p.primary)),
                    ) { Icon(painterResource(R.drawable.ic_keyboard), contentDescription = "Type a message") }
                    Button(
                        onClick = { nav.navigate("picker/chat") },
                        enabled = partner != null,
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = p.surface, contentColor = p.primary),
                    ) { Icon(painterResource(R.drawable.ic_image), contentDescription = "Send a picture") }
                    Button(
                        onClick = { nav.navigate("voicenote/note") },
                        enabled = partner != null,
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = p.surface, contentColor = p.primary),
                    ) { Icon(painterResource(R.drawable.ic_mic), contentDescription = "Record a voice message") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("👍", "OK", "On my way").forEach { q ->
                        CompactChip(
                            onClick = { scope.launch { g.engine.messenger.sendText(q) } },
                            enabled = partner != null,
                            label = { Text(q, fontSize = 11.sp) },
                            colors = ChipDefaults.chipColors(backgroundColor = p.surface, contentColor = p.text),
                        )
                    }
                }
            }
        }
    }
}

private val animsEnabled: Boolean get() = WristRadarApp.graph.settings.current.animations != com.wristradar.data.AnimationLevel.OFF

@Composable
private fun MessageBubble(m: ChatMessage, animateIn: Boolean, onImage: () -> Unit, onRetry: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val on = animationsOn()
    val anim = remember { Animatable(if (animateIn && on) 0f else 1f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(280)) }
    val bubble = if (m.outgoing) p.outgoing else p.incoming
    val textColor = if (bubble.luminanceSafe() > 0.5f) Color.Black else p.text
    val shape = RoundedCornerShape(
        topStart = 14.dp, topEnd = 14.dp,
        bottomStart = if (m.outgoing) 14.dp else 4.dp, bottomEnd = if (m.outgoing) 4.dp else 14.dp,
    )
    Column(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = anim.value
            translationY = (1f - anim.value) * 30f
        },
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier.widthIn(max = 150.dp).clip(shape).background(bubble)
                .clickable(enabled = (m.isImage && !m.isAudio) || m.status == MsgStatus.FAILED) {
                    if (m.status == MsgStatus.FAILED && m.outgoing) onRetry() else onImage()
                }
                .padding(if (m.isImage) 3.dp else 8.dp)
        ) {
            if (m.isAudio) AudioContent(m) else if (m.isImage) ImageContent(m) else Text(m.text, color = textColor, fontSize = 14.sp)
        }
        val time = DateFormat.getTimeFormat(ctx).format(Date(m.localTime))
        val status = if (!m.outgoing) "" else when (m.status) {
            MsgStatus.PENDING -> " · sending"
            MsgStatus.TRANSFERRING -> " · ${m.progress}%"
            MsgStatus.DELIVERED -> " · ✓✓"
            MsgStatus.FAILED -> " · failed, tap to retry"
            else -> ""
        }
        Text(
            time + status, fontSize = 9.sp,
            color = if (m.status == MsgStatus.FAILED) p.danger else p.textDim,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

private fun Color.luminanceSafe(): Float = (0.2126f * red + 0.7152f * green + 0.0722f * blue)

@Composable
private fun ImageContent(m: ChatMessage) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    var bmp by remember(m.id) { mutableStateOf<Bitmap?>(null) }
    val loadable = m.outgoing || m.status == MsgStatus.RECEIVED
    LaunchedEffect(m.id, loadable) { if (loadable && bmp == null) bmp = g.messages.loadBitmap(m.id) }
    val w = 130f
    val ratio = if (m.width > 0 && m.height > 0) m.height.toFloat() / m.width else 0.75f
    val h = (w * ratio).coerceIn(60f, 170f)
    Box(Modifier.width(w.dp).height(h.dp).clip(RoundedCornerShape(11.dp)).background(p.surface), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = "Picture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        val inFlight = m.status == MsgStatus.TRANSFERRING || (m.outgoing && m.status == MsgStatus.PENDING)
        if (inFlight) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = m.progress / 100f, modifier = Modifier.size(40.dp), indicatorColor = p.primary)
                Text("${m.progress}%", fontSize = 10.sp, color = Color.White)
            }
        }
        if (m.status == MsgStatus.FAILED && bmp == null) Text("Transfer failed", fontSize = 11.sp, color = p.danger)
    }
}

@Composable
fun ImageViewerScreen(id: String) {
    val g = WristRadarApp.graph
    var bmp by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) { bmp = g.messages.loadBitmap(id) }
    var scale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val state = androidx.compose.foundation.gestures.rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale <= 1.01f) androidx.compose.ui.geometry.Offset.Zero else offset + pan * scale
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e ->
                scale = (scale * (1f + e.verticalScrollPixels / 500f)).coerceIn(1f, 5f)
                if (scale <= 1.01f) offset = androidx.compose.ui.geometry.Offset.Zero
                true
            }
            .focusRequester(focus).focusable()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f
                })
            }
            .transformable(state),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(), contentDescription = "Picture", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
        } ?: CircularProgressIndicator()
    }
}

@Composable
private fun AudioContent(m: ChatMessage) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val playing by com.wristradar.audio.VoicePlayer.playing.collectAsState()
    val progress by com.wristradar.audio.VoicePlayer.progress.collectAsState()
    val isMe = playing == m.id
    val ready = m.outgoing || m.status == MsgStatus.RECEIVED
    Row(
        Modifier.width(132.dp).clickable(enabled = ready) {
            scope.launch {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { g.messages.loadImageBytes(m.id) }
                com.wristradar.audio.VoicePlayer.toggle(m.id, bytes)
            }
        }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(p.primary), contentAlignment = Alignment.Center) {
            if (isMe) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(3.dp).height(10.dp).background(p.onColor(p.primary)))
                Box(Modifier.width(3.dp).height(10.dp).background(p.onColor(p.primary)))
            } else Icon(Icons.Filled.PlayArrow, "Play", tint = p.onColor(p.primary), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.text.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(if (isMe) progress else 0f).height(4.dp).background(p.text))
            }
            val s = m.durationMs / 1000
            Text(if (ready) "🎤 %d:%02d".format(s / 60, s % 60) else "${m.progress}%", fontSize = 10.sp, color = p.textDim)
        }
    }
}
