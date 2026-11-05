package com.wristradar.ui.components

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.wristradar.ble.DistanceEstimator
import com.wristradar.core.Phase
import com.wristradar.data.AnimationLevel
import com.wristradar.ui.theme.LocalAnimation
import com.wristradar.ui.theme.LocalButtonStyle
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.Mono
import com.wristradar.ui.theme.WrType
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun animationsOn(): Boolean = LocalAnimation.current != AnimationLevel.OFF

@Composable
fun fullAnimations(): Boolean = LocalAnimation.current == AnimationLevel.FULL

/** Rotating radar sweep with optional blips (angle radians, radius 0..1). */
@Composable
fun RadarSweep(size: Dp, blips: List<Pair<Float, Float>> = emptyList(), active: Boolean = true) {
    val p = LocalPalette.current
    val animate = active && animationsOn()
    val period = if (fullAnimations()) 2600 else 5200
    val t = rememberInfiniteTransition(label = "radar")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "sweep")
    val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val a = if (animate) angle else 45f
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = center
        for (i in 1..3) drawCircle(p.primary.copy(alpha = 0.18f + 0.1f * i), radius = r * i / 3f, center = c, style = Stroke(1.2.dp.toPx()))
        drawLine(p.primary.copy(alpha = 0.15f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(p.primary.copy(alpha = 0.15f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        rotate(a, c) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent, 0.75f to Color.Transparent, 1f to p.primary.copy(alpha = 0.55f), center = c,
                ),
                startAngle = 0f, sweepAngle = 360f, useCenter = true,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            )
            drawLine(p.primary, c, Offset(c.x + r, c.y), 2.dp.toPx())
        }
        blips.forEach { (ang, rad) ->
            val pos = Offset(c.x + cos(ang) * r * rad, c.y + sin(ang) * r * rad)
            drawCircle(p.primary.copy(alpha = 0.25f * (if (animate) pulse else 1f)), 8.dp.toPx(), pos)
            drawCircle(p.text, 3.dp.toPx(), pos)
        }
        drawCircle(p.primary, 3.dp.toPx(), c)
    }
}

@Composable
fun phaseColor(phase: Phase): Color {
    val p = LocalPalette.current
    return when (phase) {
        Phase.CONNECTED -> p.connection
        Phase.FAILED, Phase.BLUETOOTH_OFF, Phase.NO_PERMISSION, Phase.UNSUPPORTED -> p.danger
        Phase.NOT_PAIRED, Phase.STANDBY -> p.textDim
        else -> p.primary
    }
}

/** Glowing status light; pulses while busy. */
@Composable
fun StatusDot(phase: Phase, size: Dp = 9.dp) {
    val color = phaseColor(phase)
    val t = rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    val alpha = if (phase.busy && animationsOn()) pulse else 1f
    Box(
        Modifier.size(size + 8.dp).drawBehind {
            drawCircle(color.copy(alpha = 0.25f * alpha), radius = this.size.minDimension / 2)
            drawCircle(color.copy(alpha = alpha), radius = size.toPx() / 2)
        }
    )
}

@Composable
fun StatusLine(phase: Phase, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        StatusDot(phase)
        Spacer(Modifier.width(4.dp))
        Text(phase.label, style = WrType.label, color = phaseColor(phase))
    }
}

@Composable
fun SignalBars(rssi: Int?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val bars = DistanceEstimator.bars(rssi)
    Row(modifier.height(12.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                Modifier.padding(horizontal = 1.dp).width(3.dp).height((3 * i).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= bars) p.primary else p.textDim.copy(alpha = 0.3f))
            )
        }
    }
}

/** Circular avatar with glow ring. Falls back to a generated initials disc. */
@Composable
fun Avatar(bitmap: Bitmap?, name: String?, size: Dp, ring: Boolean = true) {
    val p = LocalPalette.current
    val img = remember(bitmap) { bitmap?.asImageBitmap() }
    val mod = Modifier.size(size).clip(CircleShape).let {
        if (ring) it.border(1.5.dp, Brush.linearGradient(listOf(p.primary, p.secondary)), CircleShape) else it
    }
    if (img != null) {
        Image(img, contentDescription = name, modifier = mod, contentScale = ContentScale.Crop)
    } else {
        Box(
            mod.background(Brush.linearGradient(listOf(p.secondary, p.primary.copy(alpha = 0.6f)))),
            contentAlignment = Alignment.Center,
        ) {
            val initials = (name ?: "?").split(" ", "-", "_").filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
            Text(initials, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, color = Color.White)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = WrType.label, color = LocalPalette.current.primary,
        textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 11.sp, color = LocalPalette.current.textDim, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp),
    )
}

/** Standard full-width chip in the WristRadar style. */
@Composable
fun WrChip(
    label: String,
    onClick: () -> Unit,
    secondary: String? = null,
    icon: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val p = LocalPalette.current
    val bs = LocalButtonStyle.current
    val colors = when {
        bs.gradient && !danger -> ChipDefaults.gradientBackgroundChipColors(
            startBackgroundColor = p.primary.copy(alpha = if (primary) 0.7f else 0.45f),
            endBackgroundColor = p.secondary.copy(alpha = if (primary) 0.5f else 0.28f),
            contentColor = p.text, secondaryContentColor = p.text.copy(alpha = 0.75f), iconColor = p.text,
        )
        primary -> ChipDefaults.gradientBackgroundChipColors(
            startBackgroundColor = p.primary.copy(alpha = 0.55f),
            endBackgroundColor = p.secondary.copy(alpha = 0.35f),
            contentColor = p.text,
        )
        danger -> ChipDefaults.chipColors(backgroundColor = p.danger.copy(alpha = 0.22f), contentColor = p.danger)
        else -> ChipDefaults.chipColors(backgroundColor = p.surface, contentColor = p.text, secondaryContentColor = p.textDim, iconColor = p.primary)
    }
    Chip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) } },
        icon = icon,
        colors = colors,
        shape = bs.shape,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun Badge(count: Int) {
    if (count <= 0) return
    val p = LocalPalette.current
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(p.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else "$count", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = p.onColor(p.primary))
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
        Text(key.uppercase(), style = WrType.label, color = p.textDim)
        Text(value, fontSize = 13.sp, color = p.text)
    }
}

@Composable
fun Swatch(color: Color, size: Dp = 22.dp, selected: Boolean = false) {
    val p = LocalPalette.current
    Box(
        Modifier.size(size).clip(CircleShape).background(color)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.text else p.textDim.copy(alpha = 0.4f), CircleShape)
    )
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
