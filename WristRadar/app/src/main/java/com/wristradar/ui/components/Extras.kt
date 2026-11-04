package com.wristradar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ------------------------------------------------------------------ relative time

fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    if (then <= 0) return "never"
    val s = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "just now"
        s < 60 -> "$s seconds ago"
        s < 3600 -> (s / 60).let { "$it minute${if (it == 1L) "" else "s"} ago" }
        s < 86_400 -> (s / 3600).let { "$it hour${if (it == 1L) "" else "s"} ago" }
        s < 2_592_000 -> (s / 86_400).let { "$it day${if (it == 1L) "" else "s"} ago" }
        s < 31_536_000 -> (s / 2_592_000).let { "$it month${if (it == 1L) "" else "s"} ago" }
        else -> (s / 31_536_000).let { "$it year${if (it == 1L) "" else "s"} ago" }
    }
}

/** "Online" / "Last online 5 minutes ago", refreshed every second. */
@Composable
fun presenceText(connected: Boolean, lastSeen: Long): String {
    val now by produceNow()
    return if (connected) "Online" else "Last online " + relativeTime(lastSeen, now)
}

@Composable
fun produceNow(): androidx.compose.runtime.State<Long> =
    androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }

// ------------------------------------------------------------------ top banner (iPhone-style)

data class BannerData(val title: String, val text: String, val id: Long = System.nanoTime())

object BannerBus {
    val flow = MutableStateFlow<BannerData?>(null)
    fun show(title: String, text: String) { flow.value = BannerData(title, text) }
}

/** Drops in from the top, auto-hides after 4 s, swipe up to dismiss, tap to open the chat. */
@Composable
fun TopBanner(onOpen: () -> Unit) {
    val p = LocalPalette.current
    val data by BannerBus.flow.collectAsState()
    val g = WristRadarApp.graph
    val avatar by g.profile.partnerAvatar.collectAsState()
    LaunchedEffect(data?.id) { if (data != null) { delay(4000); BannerBus.flow.value = null } }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = data != null,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 400f)) { -it } + fadeIn(),
        exit = slideOutVertically(tween(220)) { -it } + fadeOut(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val d = data ?: BannerData("", "")
        LaunchedEffect(d.id) { drag.snapTo(0f) }
        Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.offset { IntOffset(0, drag.value.roundToInt()) }
                    .fillMaxWidth(0.82f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(p.surface.copy(alpha = 0.97f))
                    .border(1.dp, p.primary.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtMost(0f)) } },
                        onDragStopped = {
                            if (drag.value < -40f) BannerBus.flow.value = null else scope.launch { drag.animateTo(0f) }
                        },
                    )
                    .clickable { BannerBus.flow.value = null; onOpen() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(avatar, d.title, 26.dp, ring = false)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.text, fontSize = 11.sp, color = p.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ swipe slider

/**
 * "slide to answer" / "slide to hang up". Calls [onComplete] once dragged past 80 %.
 */
@Composable
fun SwipeSlider(label: String, color: Color, icon: ImageVector, onComplete: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val x = remember { Animatable(0f) }
    val shimmer by rememberInfiniteTransition(label = "sh").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    BoxWithConstraints(
        Modifier.fillMaxWidth(0.86f).height(46.dp).clip(RoundedCornerShape(23.dp))
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.18f), color.copy(alpha = 0.42f))))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(23.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        val thumb = 40.dp
        val maxPx = with(LocalDensity.current) { (maxWidth - thumb - 6.dp).toPx() }
        Text(
            label, fontSize = 12.sp, color = p.text,
            modifier = Modifier.fillMaxWidth().padding(start = 40.dp).alpha(shimmer * (1f - x.value / maxPx).coerceIn(0f, 1f)),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box(
            Modifier.padding(3.dp).offset { IntOffset(x.value.roundToInt(), 0) }.size(thumb).clip(CircleShape).background(color)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { dx -> scope.launch { x.snapTo((x.value + dx).coerceIn(0f, maxPx)) } },
                    onDragStopped = {
                        if (x.value > maxPx * 0.8f) { x.animateTo(maxPx, tween(120)); onComplete() }
                        else x.animateTo(0f, spring(dampingRatio = 0.6f))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
    }
}

// ------------------------------------------------------------------ typing dots

@Composable
fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(450, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(6.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}

@Composable
fun VBox(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
