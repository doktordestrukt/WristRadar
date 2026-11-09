package com.wristradar.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Typography
import com.wristradar.data.AnimationLevel
import com.wristradar.data.ButtonShape
import com.wristradar.data.FontChoice
import com.wristradar.data.Settings
import com.wristradar.data.ThemeColors

data class WrPalette(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val textDim: Color,
    val incoming: Color,
    val outgoing: Color,
    val connection: Color,
    val danger: Color = Color(0xFFFF5370),
    val warning: Color = Color(0xFFFFC857),
) {
    fun onColor(c: Color): Color = if (c.luminance() > 0.5f) Color.Black else Color.White
}

fun ThemeColors.toPalette() = WrPalette(
    primary = Color(primary), secondary = Color(secondary), background = Color(background),
    surface = Color(surface), text = Color(text), textDim = Color(text).copy(alpha = 0.62f),
    incoming = Color(incoming), outgoing = Color(outgoing), connection = Color(connection),
)

val LocalPalette = staticCompositionLocalOf { com.wristradar.data.ThemePresets.default.colors.toPalette() }
val LocalAnimation = staticCompositionLocalOf { AnimationLevel.FULL }

/** User's button look: shape and whether every button gets a gradient fill. */
data class WrButtonStyle(val shape: Shape, val gradient: Boolean)
val LocalButtonStyle = staticCompositionLocalOf { WrButtonStyle(RoundedCornerShape(50), false) }

fun FontChoice.family(): FontFamily = when (this) {
    FontChoice.DEFAULT -> FontFamily.Default
    FontChoice.ROUNDED -> FontFamily.SansSerif
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.CURSIVE -> FontFamily.Cursive
    FontChoice.CONDENSED -> runCatching { FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)) }.getOrDefault(FontFamily.SansSerif)
}

fun ButtonShape.shape(): Shape = when (this) {
    ButtonShape.ROUND -> RoundedCornerShape(50)
    ButtonShape.SOFT -> RoundedCornerShape(12.dp)
    ButtonShape.SQUARE -> RoundedCornerShape(3.dp)
}

val Mono = FontFamily.Monospace

object WrType {
    val title = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 3.sp)
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.5.sp)
    val code = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 4.sp)
}

@Composable
fun WristRadarTheme(settings: Settings, content: @Composable () -> Unit) {
    val p = settings.colors.toPalette()
    val colors = Colors(
        primary = p.primary,
        primaryVariant = p.secondary,
        secondary = p.secondary,
        secondaryVariant = p.secondary,
        background = p.background,
        surface = p.surface,
        error = p.danger,
        onPrimary = p.onColor(p.primary),
        onSecondary = p.onColor(p.secondary),
        onBackground = p.text,
        onSurface = p.text,
        onSurfaceVariant = p.textDim,
        onError = Color.Black,
    )
    val bg = settings.gradient?.takeIf { it.size >= 2 }?.let { g -> Brush.linearGradient(g.map { Color(it) }) }
    MaterialTheme(colors = colors, typography = Typography(defaultFontFamily = settings.font.family())) {
        CompositionLocalProvider(
            LocalPalette provides p, LocalAnimation provides settings.animations,
            LocalButtonStyle provides WrButtonStyle(settings.buttonShape.shape(), settings.buttonGradient),
            LocalGlass provides GlassStyle(settings.liquidGlass, settings.iconPack),
        ) {
            val fxOn = settings.liquidGlass && settings.animations != AnimationLevel.OFF
            TiltSensor(fxOn && settings.glassMotion)
            Box(Modifier.fillMaxSize().let { if (bg != null) it.background(bg) else it.background(p.background) }) {
                val g = settings.gradient
                if (settings.glassBackground && g != null) LiquidBackground(g.map { Color(it) })
                GlassOverlay(ripples = fxOn && settings.glassRipple, nightTint = settings.liquidGlass && settings.nightTint) { content() }
                if (settings.brightness < 0.99f) {
                    // Interface dimming overlay (does not intercept touches).
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - settings.brightness).coerceIn(0f, 0.7f))))
                }
            }
        }
    }
}
