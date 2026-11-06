package com.wristradar.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Live glass effects:
 *  - tilt shimmer: a light gleam slides across glass as you tilt your wrist (accelerometer)
 *  - parallax: the liquid background ribbons drift with the tilt
 *  - ripple: taps send a ring through the glass
 *  - night tint: glass dims itself in the evening
 * The sensor only runs while the app is on screen.
 */
object GlassFx {
    /** -1..1, left/right tilt (smoothed). */
    var tiltX by mutableFloatStateOf(0f)
    /** -1..1, forward/back tilt (smoothed). */
    var tiltY by mutableFloatStateOf(0f)
    var motionOn by mutableFloatStateOf(0f)   // 0 = off, 1 = on (float so draw code can read it cheaply)
}

/** Registers the accelerometer while [enabled] and this composable is on screen. */
@Composable
fun TiltSensor(enabled: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(enabled) {
        GlassFx.motionOn = if (enabled) 1f else 0f
        if (!enabled) { GlassFx.tiltX = 0f; GlassFx.tiltY = 0f; return@DisposableEffect onDispose { } }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val tx = (-e.values[0] / 6f).coerceIn(-1f, 1f)
                val ty = (e.values[1] / 6f).coerceIn(-1f, 1f)
                // low-pass so the gleam glides instead of jittering
                GlassFx.tiltX += (tx - GlassFx.tiltX) * 0.08f
                GlassFx.tiltY += (ty - GlassFx.tiltY) * 0.08f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (acc != null) sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
}

/** Diagonal light band whose position follows the wrist tilt. */
fun DrawScope.drawGleam(strength: Float = 1f) {
    if (GlassFx.motionOn < 0.5f) return
    val u = (0.5f + GlassFx.tiltX * 0.75f + GlassFx.tiltY * 0.25f)
    if (u < -0.2f || u > 1.2f) return
    val a = 0.30f * strength
    val stops = listOf(u - 0.16f to 0f, u to a, u + 0.16f to 0f)
        .map { (pos, al) -> pos.coerceIn(0f, 1f) to Color.White.copy(alpha = al) }
        .distinctBy { it.first }
    if (stops.size < 2) return
    drawRect(Brush.linearGradient(*stops.toTypedArray(), start = Offset.Zero, end = Offset(size.width, size.height)))
}

/** Adds the moving gleam on top of anything (used for buttons). */
fun Modifier.glassGleam(strength: Float = 1f): Modifier = this.drawWithContent { drawContent(); drawGleam(strength) }

private class Ripple(val at: Offset, val start: Long)

/**
 * Wraps the screen: watches taps without stealing them, and draws expanding glass rings
 * plus the evening tint on top.
 */
@Composable
fun GlassOverlay(ripples: Boolean, nightTint: Boolean, content: @Composable () -> Unit) {
    val list = androidx.compose.runtime.remember { mutableStateListOf<Ripple>() }
    val now = androidx.compose.runtime.remember { mutableLongStateOf(0L) }
    val night by produceState(isNight(), nightTint) {
        while (true) { value = isNight(); delay(60_000) }
    }
    LaunchedEffect(list.size) {
        while (list.isNotEmpty()) {
            androidx.compose.runtime.withFrameMillis { t -> now.longValue = t; list.removeAll { t - it.start > RIPPLE_MS } }
        }
    }
    Box(
        Modifier.fillMaxSize().let { m ->
            if (!ripples) m else m.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)   // observe only, never consume
                        if (e.type == PointerEventType.Press) e.changes.firstOrNull()?.let { c ->
                            if (list.size < 4) list.add(Ripple(c.position, android.os.SystemClock.uptimeMillis()))
                        }
                    }
                }
            }
        }
    ) {
        content()
        if (list.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            val t = now.longValue
            for (r in list) {
                val f = ((t - r.start).toFloat() / RIPPLE_MS).coerceIn(0f, 1f)
                val rad = size.minDimension * (0.08f + 0.55f * f)
                val a = (1f - f)
                drawCircle(Color.White.copy(alpha = 0.10f * a), rad, r.at)
                drawCircle(Color.White.copy(alpha = 0.55f * a), rad, r.at, style = Stroke(width = 3f + 4f * (1f - f)))
            }
        }
        if (nightTint && night) Box(Modifier.fillMaxSize().background(Color(0xFF05020F).copy(alpha = 0.30f)))
    }
}

private const val RIPPLE_MS = 650L

private fun isNight(): Boolean {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return h >= 21 || h < 7
}
