package com.wristradar.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.LocalContentColor
import com.wristradar.data.IconPack

/** Glass look settings for the current screen. */
data class GlassStyle(val on: Boolean, val iconPack: IconPack)

val LocalGlass = staticCompositionLocalOf { GlassStyle(true, IconPack.MATCH) }

/** Frosted fill: brighter at the top like light catching curved glass. */
fun glassFill(tint: Color = Color.White, strength: Float = 1f) = Brush.verticalGradient(
    listOf(tint.copy(alpha = 0.30f * strength), tint.copy(alpha = 0.14f * strength), tint.copy(alpha = 0.20f * strength)),
)

/** Bright rim at the top-left fading out: the "liquid" edge highlight. */
fun glassRim() = Brush.linearGradient(
    listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.35f)),
)

/** Frosted glass panel with a light rim and a soft top sheen. */
fun Modifier.glass(shape: Shape, tint: Color = Color.White, strength: Float = 1f): Modifier =
    this.clip(shape)
        .background(glassFill(tint, strength), shape)
        .drawWithContent {
            drawContent()
            // top sheen
            drawRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), endY = size.height * 0.5f),
                size = size.copy(height = size.height * 0.5f),
            )
        }
        .border(1.dp, glassRim(), shape)

/**
 * Liquid background: the theme gradient with two soft flowing ribbons on top, like the
 * curved glass wallpapers in liquid-glass home screens. Drawn once, very cheap.
 */
@Composable
fun LiquidBackground(colors: List<Color>) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(colors, start = Offset(0f, 0f), end = Offset(size.width, size.height)))
        val w = size.width; val h = size.height
        val ribbon1 = Path().apply {
            moveTo(-w * 0.1f, h * 0.30f)
            cubicTo(w * 0.30f, h * 0.05f, w * 0.55f, h * 0.65f, w * 1.1f, h * 0.35f)
            lineTo(w * 1.1f, h * 0.55f)
            cubicTo(w * 0.60f, h * 0.85f, w * 0.30f, h * 0.25f, -w * 0.1f, h * 0.52f)
            close()
        }
        drawPath(ribbon1, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)), Offset(0f, 0f), Offset(w, h)))
        val ribbon2 = Path().apply {
            moveTo(w * 0.2f, h * 1.1f)
            cubicTo(w * 0.35f, h * 0.70f, w * 0.75f, h * 0.95f, w * 1.1f, h * 0.62f)
            lineTo(w * 1.1f, h * 1.1f)
            close()
        }
        drawPath(ribbon2, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Black.copy(alpha = 0.10f)), startY = h * 0.6f, endY = h))
        // glossy highlight blob
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), center = Offset(w * 0.25f, h * 0.2f), radius = w * 0.35f),
            radius = w * 0.35f, center = Offset(w * 0.25f, h * 0.2f),
        )
    }
}

/**
 * An icon on a glossy rounded tile (icon packs). Works for Material icons (tinted white)
 * and emoji text. CLASSIC pack draws the icon as-is.
 */
@Composable
fun IconTile(size: Dp = 26.dp, content: @Composable BoxScope.() -> Unit) {
    val glass = LocalGlass.current
    val p = LocalPalette.current
    val pack = glass.iconPack
    if (pack == IconPack.CLASSIC) { Box(contentAlignment = Alignment.Center, content = content); return }
    val colors = pack.tile?.map { Color(it) } ?: listOf(p.secondary.copy(alpha = 0.95f), p.primary.copy(alpha = 0.75f), p.secondary.copy(alpha = 0.85f))
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        Modifier.size(size).clip(shape)
            .background(Brush.linearGradient(colors), shape)
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), endY = this.size.height * 0.55f))
            }
            .border(1.dp, glassRim(), shape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}
