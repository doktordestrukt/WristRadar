package com.wristradar.ui.screens

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.data.ProfileEffect
import com.wristradar.data.ProfileStore
import com.wristradar.ui.UiShared
import com.wristradar.ui.components.Avatar
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.animationsOn
import com.wristradar.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ------------------------------------------------------------------------------- crop & resize

/**
 * Crop and resize a picture for the profile picture (circle) or banner (wide).
 * Pinch or turn the bezel to zoom, drag to move, ⟳ to rotate.
 */
@Composable
fun CropScreen(nav: NavHostController, purpose: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var src by remember { mutableStateOf(UiShared.pickedBitmap) }
    val bmp = src
    if (bmp == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val banner = purpose == "banner"
    // Frame on screen (dp): circle for avatars, 5:2 rectangle for banners.
    val frameW = if (banner) 170.dp else 140.dp
    val frameH = if (banner) 68.dp else 140.dp
    val fw = with(density) { frameW.toPx() }
    val fh = with(density) { frameH.toPx() }
    val cover = max(fw / bmp.width, fh / bmp.height)     // zoom 1 = picture just covers the frame
    var zoom by remember(bmp) { mutableFloatStateOf(1f) }
    var off by remember(bmp) { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun clamp(o: Offset, z: Float): Offset {
        val s = cover * z
        val maxX = max(0f, (bmp.width * s - fw) / 2f)
        val maxY = max(0f, (bmp.height * s - fh) / 2f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    val tState = rememberTransformableState { zc, pan, _ ->
        zoom = (zoom * zc).coerceIn(1f, 6f)
        off = clamp(off + pan, zoom)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e -> zoom = (zoom * (1f + e.verticalScrollPixels / 400f)).coerceIn(1f, 6f); off = clamp(off, zoom); true }
            .focusRequester(focus).focusable()
            .transformable(tState),
        contentAlignment = Alignment.Center,
    ) {
        val img = remember(bmp) { bmp.asImageBitmap() }
        Canvas(Modifier.fillMaxSize()) {
            val s = cover * zoom
            val dw = bmp.width * s; val dh = bmp.height * s
            val left = center.x - dw / 2 + off.x; val top = center.y - dh / 2 + off.y
            drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()))
            // Dim everything outside the frame.
            val frame = Rect(center.x - fw / 2, center.y - fh / 2, center.x + fw / 2, center.y + fh / 2)
            val hole = Path().apply { if (banner) addRoundRect(RoundRect(frame, CornerRadius(12.dp.toPx()))) else addOval(frame) }
            clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
            if (banner) drawRoundRect(Color.White, frame.topLeft, frame.size, CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
            else drawOval(Color.White, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
        }
        Text(if (banner) "Banner" else "Profile picture", fontSize = 11.sp, color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
        Text("Pinch or turn bezel to zoom · drag to move", fontSize = 8.sp, color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CropBtn("−") { zoom = (zoom / 1.2f).coerceAtLeast(1f); off = clamp(off, zoom) }
            CropBtn("+") { zoom = (zoom * 1.2f).coerceAtMost(6f); off = clamp(off, zoom) }
            CropBtn("⟳") {
                val m = Matrix().apply { postRotate(90f) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                UiShared.pickedBitmap = r; src = r
            }
            CropBtn(if (busy) "…" else "Use", highlight = true) {
                if (busy) return@CropBtn
                busy = true
                val s = cover * zoom
                // Frame rectangle mapped back into picture pixels.
                val cx = bmp.width / 2f - off.x / s; val cy = bmp.height / 2f - off.y / s
                val cw = fw / s; val ch = fh / s
                val x = (cx - cw / 2).toInt().coerceIn(0, bmp.width - 1)
                val y = (cy - ch / 2).toInt().coerceIn(0, bmp.height - 1)
                val w = min(cw.toInt(), bmp.width - x).coerceAtLeast(1)
                val h = min(ch.toInt(), bmp.height - y).coerceAtLeast(1)
                scope.launch {
                    withContext(Dispatchers.Default) {
                        val cut = Bitmap.createBitmap(bmp, x, y, w, h)
                        if (banner) g.profile.setMyBanner(Bitmap.createScaledBitmap(cut, ProfileStore.BANNER_W, ProfileStore.BANNER_H, true))
                        else g.profile.setMyAvatar(Bitmap.createScaledBitmap(cut, 256, 256, true))
                    }
                    g.engine.pushProfile()
                    UiShared.pickedBitmap = null
                    nav.popBackStack("profile", inclusive = false)
                }
            }
        }
    }
}

@Composable
private fun CropBtn(label: String, highlight: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (highlight) p.onColor(p.primary) else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (highlight) p.primary else Color.White.copy(alpha = 0.2f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ------------------------------------------------------------------------------- banner + avatar header

/** Profile header: banner across the top with the avatar (and its effect) overlapping it. */
@Composable
fun ProfileHeader(banner: Bitmap?, avatar: Bitmap?, name: String?, effect: ProfileEffect, onBanner: (() -> Unit)? = null, onAvatar: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val bImg = remember(banner) { banner?.asImageBitmap() }
    Box(Modifier.width(160.dp).height(104.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.width(160.dp).height(64.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(p.secondary, p.primary.copy(alpha = 0.7f))))
                .let { if (onBanner != null) it.clickable(onClick = onBanner) else it },
        ) {
            if (bImg != null) Image(bImg, "Banner", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (onBanner != null) Text("+ banner", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center))
        }
        Box(Modifier.offset(y = 34.dp).let { if (onAvatar != null) it.clickable(onClick = onAvatar) else it }) {
            EffectAvatar(avatar, name, 64.dp, effect)
        }
    }
}

/** Avatar with its animated profile effect around it. */
@Composable
fun EffectAvatar(bitmap: Bitmap?, name: String?, size: Dp, effect: ProfileEffect) {
    val pad = if (effect == ProfileEffect.NONE) 0.dp else size * 0.18f
    Box(Modifier.size(size + pad * 2), contentAlignment = Alignment.Center) {
        if (effect != ProfileEffect.NONE) EffectLayer(effect, Modifier.fillMaxSize(), behind = true)
        Avatar(bitmap, name, size)
        if (effect != ProfileEffect.NONE) EffectLayer(effect, Modifier.fillMaxSize(), behind = false)
    }
}

@Composable
private fun EffectLayer(effect: ProfileEffect, modifier: Modifier, behind: Boolean) {
    val p = LocalPalette.current
    val on = animationsOn()
    val t = rememberInfiniteTransition(label = "fx")
    val spin by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val k = if (on) spin else 0.15f
    val pu = if (on) pulse else 0.5f
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        val inner = r / 1.36f   // avatar radius
        fun ring(brush: Brush, w: Float) = drawCircle(brush, inner + w / 2 + 1.dp.toPx(), c, style = Stroke(w))
        when (effect) {
            ProfileEffect.GLOW -> if (behind) {
                drawCircle(Brush.radialGradient(listOf(p.primary.copy(alpha = 0.55f + 0.25f * pu), Color.Transparent), c, r), r, c)
            } else ring(Brush.sweepGradient(listOf(p.primary, p.secondary, p.primary), c), 2.5.dp.toPx())
            ProfileEffect.RAINBOW -> if (!behind) rotate(k * 360f, c) {
                ring(Brush.sweepGradient(listOf(Color(0xFFFF4D4D), Color(0xFFFFB84D), Color(0xFFFFF04D), Color(0xFF4DFF88), Color(0xFF4DB8FF), Color(0xFFB84DFF), Color(0xFFFF4D4D)), c), 3.dp.toPx())
            }
            ProfileEffect.AURORA -> if (behind) rotate(k * 360f, c) {
                drawCircle(Brush.sweepGradient(listOf(Color(0x9955FFD5), Color(0x998A5CFF), Color(0x99FF6BD5), Color(0x9955FFD5)), c), r * 0.98f, c)
            }
            ProfileEffect.SPARKLES -> if (!behind) for (i in 0 until 7) {
                val a = (i / 7f + k * 0.3f) * 2 * PI
                val tw = ((sin((k * 12 + i) * 1.7) + 1) / 2).toFloat()
                val pos = Offset(c.x + cos(a).toFloat() * r * 0.88f, c.y + sin(a).toFloat() * r * 0.88f)
                star(pos, 3.dp.toPx() * (0.4f + tw), Color.White.copy(alpha = 0.4f + 0.6f * tw))
            }
            ProfileEffect.ORBIT -> if (!behind) for (i in 0 until 3) {
                val a = (k + i / 3f) * 2 * PI
                val pos = Offset(c.x + cos(a).toFloat() * r * 0.9f, c.y + sin(a).toFloat() * r * 0.9f)
                star(pos, 3.5.dp.toPx(), if (i == 0) p.primary else Color.White)
            }
            ProfileEffect.BUBBLES -> if (!behind) for (i in 0 until 6) {
                val ph = (k * 2 + i / 6f) % 1f
                val x = c.x + (i - 2.5f) * r * 0.32f
                val y = c.y + r - ph * r * 2
                drawCircle(Color.White.copy(alpha = 0.5f * (1 - ph)), 2.dp.toPx() + i % 3 * 1.dp.toPx(), Offset(x, y), style = Stroke(1.dp.toPx()))
            }
            ProfileEffect.SNOW -> if (!behind) for (i in 0 until 8) {
                val ph = (k * 1.5f + i / 8f) % 1f
                val x = c.x + ((i * 37) % 100 / 100f - 0.5f) * r * 1.8f + sin(ph * 6.28f) * 3.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.85f), 1.6.dp.toPx(), Offset(x, c.y - r + ph * r * 2))
            }
            ProfileEffect.FIRE -> if (behind) for (i in 0 until 14) {
                val a = i / 14f * 2 * PI
                val flick = (sin((k * 30 + i * 1.3)).toFloat() + 1) / 2
                val len = inner + (4.dp.toPx() + flick * 6.dp.toPx())
                val pos = Offset(c.x + cos(a).toFloat() * len, c.y + sin(a).toFloat() * len)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE066), Color(0xFFFF7A1A), Color.Transparent), pos, 6.dp.toPx()), 6.dp.toPx(), pos)
            }
            ProfileEffect.PETALS -> if (!behind) for (i in 0 until 6) {
                val ph = (k + i / 6f) % 1f
                val a = (i / 6f) * 2 * PI + ph * 2
                val rr = r * (0.75f + 0.25f * ph)
                val pos = Offset(c.x + cos(a).toFloat() * rr, c.y + sin(a).toFloat() * rr)
                withTransform({ rotate(ph * 360f, pos) }) {
                    drawOval(Color(0xFFFFB7D5).copy(alpha = 1 - ph * 0.6f), Offset(pos.x - 3.dp.toPx(), pos.y - 1.5.dp.toPx()), androidx.compose.ui.geometry.Size(6.dp.toPx(), 3.dp.toPx()))
                }
            }
            ProfileEffect.NONE -> Unit
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.star(c: Offset, s: Float, color: Color) {
    val path = Path().apply {
        moveTo(c.x, c.y - s); lineTo(c.x + s * 0.28f, c.y - s * 0.28f); lineTo(c.x + s, c.y)
        lineTo(c.x + s * 0.28f, c.y + s * 0.28f); lineTo(c.x, c.y + s); lineTo(c.x - s * 0.28f, c.y + s * 0.28f)
        lineTo(c.x - s, c.y); lineTo(c.x - s * 0.28f, c.y - s * 0.28f); close()
    }
    drawPath(path, color)
}

// ------------------------------------------------------------------------------- effect picker

@Composable
fun EffectPickerScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val avatar by g.profile.myAvatar.collectAsState()
    val name by g.profile.myName.collectAsState()
    var current by remember { mutableStateOf(g.profile.myEffect.value) }
    WrScreen {
        item { SectionLabel("Profile effect") }
        item { EffectAvatar(avatar, name, 56.dp, current) }
        ProfileEffect.entries.forEach { e ->
            item(key = e.name) {
                WrChip(e.label, {
                    current = e
                    g.profile.setMyEffect(e)
                    g.engine.pushProfile()
                }, primary = e == current, secondary = if (e == current) "Active" else null)
            }
        }
        item { Hint("Your friend sees your effect on your profile and in the chat.") }
    }
}
