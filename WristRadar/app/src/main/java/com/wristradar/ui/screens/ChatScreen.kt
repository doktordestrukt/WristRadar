package com.wristradar.ui.screens

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.offset
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
import com.wristradar.ui.theme.glass
import com.wristradar.ui.theme.WrType
import kotlinx.coroutines.launch
import java.util.Date

@Composable
fun ChatScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val messages by g.messages.messages.collectAsState(initial = null)
    // Only the bits the chat shows: signal/distance updates many times a second and must not
    // rebuild the message list each time.
    val phase by remember { g.engine.connection.map { it.phase }.distinctUntilChanged() }.collectAsState(g.engine.connection.value.phase)
    val remoteBattery by remember { g.engine.connection.map { it.remoteBattery }.distinctUntilChanged() }.collectAsState(g.engine.connection.value.remoteBattery)
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
    val replyTarget = ChatReply.target
    val partnerApp = g.engine.updater.state.collectAsState().value.partner
    val sendText = rememberTextInput(onClosed = { g.engine.messenger.sendTyping(false) }) { text ->
        val to = ChatReply.target?.id
        ChatReply.target = null
        val fx = ChatEffect.pending
        ChatEffect.pending = null
        scope.launch {
            g.engine.messenger.sendText(text, to)
            fx?.let { g.engine.extras.sendEffect(it) }
        }
    }

    DisposableEffect(Unit) {
        g.uiVisibility.chatVisible = true
        g.notifier.cancelMessages()
        onDispose {
            g.uiVisibility.chatVisible = false
            // Remember where the user was, so reopening the chat lands back there.
            ChatScrollMemory.index = listState.centerItemIndex
            ChatScrollMemory.offset = listState.centerItemScrollOffset
        }
    }

    val list = messages
    val byId = remember(list) { list?.associateBy { it.id }.orEmpty() }
    val lastOutId = remember(list) { list?.lastOrNull { it.outgoing && !it.isInfo && !it.isAlert }?.id }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val days = remember(list) { list?.map { java.time.Instant.ofEpochMilli(it.localTime).atZone(zone).toLocalDate() }.orEmpty() }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(list?.size) {
        if (list == null) return@LaunchedEffect
        if (!unreadComputed) {
            firstUnreadId = list.firstOrNull { !it.outgoing && !it.read && !it.isAlert }?.id
            unreadComputed = true
        }
        if (list.any { !it.read }) g.engine.messenger.sendSeen(g.messages.markAllRead())
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex <= 0) return@LaunchedEffect
        if (!restored) {
            restored = true
            val saved = ChatScrollMemory.index
            if (saved in 0..lastIndex) { listState.scrollToItem(saved, ChatScrollMemory.offset); return@LaunchedEffect }
            listState.scrollToItem(lastIndex)
            return@LaunchedEffect
        }
        // New message while open: follow it only if the user is already near the bottom.
        val nearBottom = listState.centerItemIndex >= lastIndex - 4 || list.lastOrNull()?.outgoing == true
        if (nearBottom) {
            if (animsEnabled) listState.animateScrollToItem(lastIndex) else listState.scrollToItem(lastIndex)
        }
    }

    Box(Modifier.fillMaxSize()) {
    WrScreen(state = listState) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { nav.navigate("partner") },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val pEffect by g.profile.partnerEffect.collectAsState()
                EffectAvatar(partnerAvatar, partnerName, 28.dp, pEffect)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(partnerName ?: "Partner", fontSize = 14.sp, color = p.text)
                    val mood by g.engine.extras.partnerMood.collectAsState()
                    mood?.let { Text(it, fontSize = 9.sp, color = p.textDim, maxLines = 1) }
                    val extra = listOfNotNull(
                        remoteBattery?.takeIf { phase == Phase.CONNECTED }?.let { "🔋$it%" },
                        if (g.settings.state.collectAsState().value.muted) "🔕" else null,
                    )
                    if (extra.isNotEmpty()) Text(extra.joinToString(" "), fontSize = 9.sp, color = p.textDim)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(phase, 6.dp)
                        val streak = remember(list) { list?.let { streakDays(it) } ?: 0 }
                        if (streak > 0) Text("🔥$streak ", fontSize = 10.sp, color = p.warning)
                        androidx.compose.animation.AnimatedContent(presence.partnerTyping, label = "typing") { typing ->
                            if (typing) Text("typing…", fontSize = 10.sp, color = p.primary)
                            else Text(presenceText(phase == Phase.CONNECTED, lastSeen), fontSize = 10.sp, color = phaseColor(phase))
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { if (g.engine.calls.startCall()) nav.navigate("call") },
                    enabled = phase == Phase.CONNECTED,
                    modifier = Modifier.size(30.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = p.connection.copy(alpha = 0.85f), contentColor = p.background),
                ) { Icon(Icons.Filled.Call, "Call", modifier = Modifier.size(16.dp)) }
            }
        }
        if (partner == null) item { Hint("Not paired. Pair with a watch to chat.") }
        if (partnerApp != null && partnerApp.versionCode < com.wristradar.BuildConfig.VERSION_CODE) item(key = "old-version") {
            // Placeholder: features the friend's older app can't show yet.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.warning.copy(alpha = 0.18f))
                    .clickable { nav.navigate("updates") }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val who = partnerName ?: "Your friend"
                val line = remember(partnerApp.versionCode) { UpdateNudges.random().replace("{name}", who) }
                Text(line, fontSize = 11.sp, color = p.text, textAlign = TextAlign.Center)
                Text(
                    "$who is on ${partnerApp.label}, you're on ${com.wristradar.BuildConfig.RELEASE_LABEL}. Missing: " + missingFeatures(partnerApp.versionCode) + ". Tap to send the update.",
                    fontSize = 10.sp, color = p.textDim, textAlign = TextAlign.Center,
                )
                val up = g.engine.updater
                Text(
                    "You updated ${whenAgo(up.myUpdatedAt)}" + (up.partnerUpdatedAt.takeIf { it > 0 }?.let { " · $who's version since ${whenAgo(it)}" } ?: ""),
                    fontSize = 9.sp, color = p.textDim, textAlign = TextAlign.Center,
                )
            }
        }
        if (list != null && list.isEmpty()) item { Hint("No messages yet. Say hi 👋") }
        var lastDay: java.time.LocalDate? = null
        list?.forEachIndexed { idx, m ->
            val day = days[idx]
            if (day != lastDay) {
                lastDay = day
                item(key = "day-$day") { DayDivider(day) }
            }
            if (m.id == firstUnreadId) item(key = "unread-divider") {
                Text("— NEW —", style = WrType.label, color = p.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            item(key = m.id) {
                MessageBubble(
                    m = m,
                    animateIn = m.localTime > openedAt,
                    onImage = { nav.navigate("image/${m.id}") },
                    onRetry = { scope.launch { g.engine.messenger.retry(m.id) } },
                    quoted = m.replyTo?.let { byId[it] },
                    lastOut = m.id == lastOutId,
                    onLongPress = { nav.navigate("msg/${m.id}") },
                    onReactTap = { e -> scope.launch { g.engine.messenger.react(m.id, e) } },
                    onDoubleTap = { if (!m.isAlert && !m.isInfo) scope.launch { g.engine.messenger.react(m.id, "❤️") } },
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
                if (replyTarget != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(p.surface)
                            .clickable { ChatReply.target = null }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("↩ " + snippet(replyTarget), fontSize = 10.sp, color = p.textDim, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("✕", fontSize = 11.sp, color = p.text)
                    }
                }
                ChatEffect.pending?.let { fx ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(p.primary.copy(alpha = 0.25f))
                            .clickable { ChatEffect.pending = null }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Next message: ${fx.emoji} ${fx.title}", fontSize = 10.sp, color = p.text, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("✕", fontSize = 11.sp, color = p.text)
                    }
                }
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
                    g.settings.state.collectAsState().value.quickReplies.take(3).forEach { q ->
                        CompactChip(
                            onClick = { scope.launch { g.engine.messenger.sendText(q) } },
                            enabled = partner != null,
                            label = { Text(q, fontSize = 11.sp) },
                            colors = ChipDefaults.chipColors(backgroundColor = p.surface, contentColor = p.text),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactChip(onClick = { nav.navigate("pinned") }, label = { Text("📌 Pinned", fontSize = 11.sp) },
                        colors = ChipDefaults.chipColors(backgroundColor = p.surface, contentColor = p.text))
                    CompactChip(onClick = { nav.navigate("search") }, label = { Text("🔍 Search", fontSize = 11.sp) },
                        colors = ChipDefaults.chipColors(backgroundColor = p.surface, contentColor = p.text))
                }
                Spacer(Modifier.height(4.dp))
                CompactChip(onClick = { nav.navigate("sendeffect") }, enabled = partner != null,
                    label = { Text("🎉 Send with effect", fontSize = 11.sp) },
                    colors = ChipDefaults.chipColors(backgroundColor = p.surface, contentColor = p.text))
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactChip(onClick = { g.engine.messenger.nudge() }, enabled = phase == Phase.CONNECTED,
                        label = { Text("👋 Nudge", fontSize = 11.sp) },
                        colors = ChipDefaults.chipColors(backgroundColor = p.surface, contentColor = p.text))
                    CompactChip(onClick = { nav.navigate("walk") }, label = { Text("🚶 Walk", fontSize = 11.sp) },
                        colors = ChipDefaults.chipColors(backgroundColor = p.surface, contentColor = p.text))
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactChip(onClick = { nav.navigate("fun") }, label = { Text("✨ Fun", fontSize = 11.sp) },
                        colors = ChipDefaults.chipColors(backgroundColor = p.primary.copy(alpha = 0.35f), contentColor = p.text))
                }
                Text("Hold a message to react, reply, edit, pin or delete", fontSize = 9.sp, color = p.textDim, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    // Jump back to the newest message when scrolled up.
    val total = listState.layoutInfo.totalItemsCount
    val away = total > 0 && listState.centerItemIndex < total - 5
    if (away) {
        Box(Modifier.fillMaxSize().padding(bottom = 22.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                "↓ Latest", fontSize = 11.sp, color = p.onColor(p.primary),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(p.primary.copy(alpha = 0.9f))
                    .clickable { scope.launch { listState.animateScrollToItem(total - 1) } }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    }
}

/** "Today", "Yesterday" or the date, between days in the chat. */
@Composable
private fun DayDivider(day: java.time.LocalDate) {
    val p = LocalPalette.current
    val today = java.time.LocalDate.now()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "d MMM yyyy"))
    }
    Text(label, fontSize = 10.sp, color = p.textDim, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

/** Message being replied to (set from the message menu, cleared on send). */
object ChatReply {
    var target by mutableStateOf<ChatMessage?>(null)
}

/** Features the partner's older version is missing, for the "please update" placeholder. */
/** A different "please update" line every time the placeholder appears. */
val UpdateNudges = listOf(
    "✨ {name}'s app is behind - time for a glow-up!",
    "📦 New stuff is waiting for {name}!",
    "🚀 {name} is missing out - send them the update!",
    "💅 {name}'s WristRadar needs a little refresh.",
    "🔄 {name} is on an older version.",
    "🎁 There's a new version for {name} to unwrap!",
    "⏳ {name} is living in the past - update them!",
    "🫧 {name} can't see the newest bits yet.",
)

/** "today 14:05", "yesterday", "3 days ago" or a date. */
fun whenAgo(t: Long): String {
    if (t <= 0) return "a while ago"
    val days = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
    return when {
        days <= 0 -> "today $time"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}

fun missingFeatures(partnerVersion: Int): String {
    val f = mutableListOf<String>()
    if (partnerVersion < 2) f += listOf("blue ticks", "reactions", "replies", "edits")
    if (partnerVersion < 3) f += listOf("nudges", "Bluetooth updates")
    if (partnerVersion < 4) f += listOf("games", "emoji blasts", "tap code", "status")
    if (partnerVersion < 8) f += listOf("profile banners", "profile effects")
    if (partnerVersion < 13) f += listOf("Connect 4", "Memory Match", "Dice Duel", "text formatting & spoilers")
    if (partnerVersion < 16) f += listOf("message effects")
    return f.joinToString(", ")
}

fun snippet(m: ChatMessage): String = when {
    m.isAudio -> "🎤 Voice message"
    m.isImage -> "📷 Photo"
    else -> m.text.take(60)
}

private val animsEnabled: Boolean get() = WristRadarApp.graph.settings.current.animations != com.wristradar.data.AnimationLevel.OFF

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: ChatMessage, animateIn: Boolean, onImage: () -> Unit, onRetry: () -> Unit,
    quoted: ChatMessage? = null, onLongPress: () -> Unit = {}, onReactTap: (String) -> Unit = {},
    onDoubleTap: () -> Unit = {},
    lastOut: Boolean = false,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val on = animationsOn()
    val anim = remember { Animatable(if (animateIn && on) 0f else 1f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(280)) }
    if (m.isInfo) {
        Box(Modifier.fillMaxWidth().graphicsLayer { alpha = anim.value }, contentAlignment = Alignment.Center) {
            Text(
                m.text, color = p.text, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 170.dp).clip(RoundedCornerShape(10.dp))
                    .background(p.surface).padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        return
    }
    if (m.isAlert) {
        // Intercepted / tampered message that was blocked: shown in red, centred.
        Box(Modifier.fillMaxWidth().graphicsLayer { alpha = anim.value }, contentAlignment = Alignment.Center) {
            Text(
                m.text, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 170.dp).clip(RoundedCornerShape(10.dp))
                    .background(p.danger.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        return
    }
    val bubble = if (m.outgoing) p.outgoing else p.incoming
    val textColor = if (bubble.alpha < 0.9f || com.wristradar.ui.theme.LocalGlass.current.on) p.text else if (bubble.luminanceSafe() > 0.5f) Color.Black else p.text
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
        val glassOn = com.wristradar.ui.theme.LocalGlass.current.on
        val hasSpoiler = !m.isImage && com.wristradar.ui.components.RichText.hasSpoiler(m.text)
        var reveal by remember(m.id) { mutableStateOf(false) }
        Box(
            Modifier.widthIn(max = 150.dp).let {
                if (glassOn) it.then(Modifier.glass(shape, tint = if (m.outgoing) p.primary else Color.White, strength = if (m.outgoing) 1.6f else 1f))
                else it.clip(shape).background(bubble)
            }
                .combinedClickable(
                    onClick = {
                        if (hasSpoiler) reveal = !reveal
                        else if (m.status == MsgStatus.FAILED && m.outgoing) onRetry()
                        else if (m.isImage && !m.isAudio) onImage()
                    },
                    onLongClick = onLongPress,
                    onDoubleClick = onDoubleTap,
                )
                .padding(if (m.isImage) 3.dp else 8.dp)
        ) {
            Column {
                if (m.replyTo != null) {
                    Text(
                        "↩ " + (quoted?.let { snippet(it) } ?: "deleted message"), fontSize = 10.sp, maxLines = 2,
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 3.dp).clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.18f)).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                if (m.isAudio) AudioContent(m) else if (m.isImage) ImageContent(m) else {
                    val size = WristRadarApp.graph.settings.state.collectAsState().value.chatText.sp
                    val rt = com.wristradar.ui.components.RichText
                    if (rt.isJumboEmoji(m.text)) Text(m.text.trim(), fontSize = 30.sp)
                    else if (rt.hasFormatting(m.text)) Text(remember(m.text, reveal, textColor) { rt.format(m.text, textColor, reveal) }, color = textColor, fontSize = size.sp)
                    else Text(m.text, color = textColor, fontSize = size.sp)
                }
            }
        }
        val reacts = listOfNotNull(m.reactTheirs, m.reactMine)
        if (reacts.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 4.dp).offset(y = (-4).dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                reacts.distinct().forEach { e ->
                    val count = reacts.count { it == e }
                    val mine = m.reactMine == e
                    Text(
                        e + if (count > 1) " 2" else "", fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (mine) p.primary.copy(alpha = 0.45f) else p.surface)
                            .clickable { onReactTap(e) }.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        val time = DateFormat.getTimeFormat(ctx).format(Date(m.localTime))
        val status = if (!m.outgoing) "" else when (m.status) {
            MsgStatus.PENDING -> " · sending"
            MsgStatus.TRANSFERRING -> " · ${m.progress}%"
            MsgStatus.DELIVERED -> if (lastOut) " · Delivered ✓✓" else " · ✓✓"
            MsgStatus.SEEN -> if (lastOut) " · Read ✓✓" else " · ✓✓"
            MsgStatus.FAILED -> " · failed, tap to retry"
            else -> ""
        }
        Text(
            (if (m.pinned) "📌 " else "") + time + (if (m.edited) " · edited" else "") + status, fontSize = 9.sp,
            color = when (m.status) {
                MsgStatus.FAILED -> p.danger
                MsgStatus.SEEN -> SeenBlue        // blue ticks = seen
                else -> p.textDim
            },
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
            // Waveform: bar heights from the recording's loudness; played part is solid.
            val bars by androidx.compose.runtime.produceState<FloatArray?>(null, m.id, ready) {
                if (ready) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    WaveformCache.get(m.id) { g.messages.loadImageBytes(m.id) }
                }
            }
            val wave = bars
            val played = if (isMe) progress else 0f
            val on = p.text; val off = p.text.copy(alpha = 0.3f)
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                val n = wave?.size ?: 24
                val bw = size.width / n
                for (i in 0 until n) {
                    val h = ((wave?.get(i) ?: 0.15f).coerceIn(0.12f, 1f)) * size.height
                    drawRoundRect(
                        if ((i + 0.5f) / n <= played) on else off,
                        topLeft = androidx.compose.ui.geometry.Offset(i * bw + bw * 0.2f, (size.height - h) / 2),
                        size = androidx.compose.ui.geometry.Size(bw * 0.6f, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.3f),
                    )
                }
            }
            val s = m.durationMs / 1000
            Text(if (ready) "🎤 %d:%02d".format(s / 60, s % 60) else "${m.progress}%", fontSize = 10.sp, color = p.textDim)
        }
    }
}

/** Waveform bars per voice message, computed once from the stored audio. */
object WaveformCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    fun get(id: String, load: () -> ByteArray?): FloatArray? = cache[id] ?: run {
        val pcm = load()?.let { runCatching { com.wristradar.audio.Adpcm.unpackVoice(it) }.getOrNull() } ?: return null
        if (pcm.isEmpty()) return null
        val n = 24
        val per = maxOf(1, pcm.size / n)
        val raw = FloatArray(n) { b ->
            var sum = 0.0; var c = 0
            var i = b * per
            while (i < minOf(pcm.size, (b + 1) * per)) { sum += kotlin.math.abs(pcm[i].toInt()); c++; i += 4 }
            if (c == 0) 0f else (sum / c).toFloat()
        }
        val max = raw.maxOrNull()?.takeIf { it > 0f } ?: 1f
        raw.map { kotlin.math.sqrt(it / max) }.toFloatArray().also { cache[id] = it }
    }
}

/** Blue used for "seen" ticks (readable on every theme). */
private val SeenBlue = Color(0xFF34A6FF)

/** Last scroll position in the chat, kept for the app's lifetime. */
object ChatScrollMemory {
    var index: Int = -1
    var offset: Int = 0
}
