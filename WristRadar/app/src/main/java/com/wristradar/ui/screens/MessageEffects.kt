package com.wristradar.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.navigation.NavHostController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import com.wristradar.WristRadarApp
import com.wristradar.core.MessageEffect
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Effect chosen for the next message (cleared once sent). */
object ChatEffect {
    var pending by mutableStateOf<MessageEffect?>(null)
}

@Composable
fun EffectPickerSendScreen(nav: NavHostController) {
    WrScreen {
        item { SectionLabel("Send with effect") }
        MessageEffect.entries.forEach { e ->
            item(key = e.name) {
                WrChip("${e.emoji} ${e.title}", { ChatEffect.pending = e; WristRadarApp.graph.engine.extras.previewEffect(e); nav.popBackStack() },
                    primary = ChatEffect.pending == e, tile = false)
            }
        }
        item { WrChip("No effect", { ChatEffect.pending = null; nav.popBackStack() }, tile = false) }
    }
}

private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val size: Float, val color: Color, var rot: Float, val spin: Float, val delay: Float)

private val Palette = listOf(Color(0xFFFF4F8B), Color(0xFFFFD23F), Color(0xFF3EE8B5), Color(0xFF6C8CFF), Color(0xFFC77DFF), Color(0xFFFF8A3D))

/**
 * Full-screen message effect (above every screen). Particles are simulated in plain floats and
 * drawn once per frame on one Canvas: ~40-60 particles, 3 seconds, then it removes itself.
 */
@Composable
fun MessageEffectOverlay() {
    val g = WristRadarApp.graph
    val fx by g.engine.extras.effect.collectAsState()
    val e = fx ?: return
    val particles = remember(e) { mutableListOf<Particle>() }
    var t by remember(e) { mutableFloatStateOf(0f) }
    var size by remember(e) { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    LaunchedEffect(e) {
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val sec = (now - start) / 1000f
            if (sec > DURATION) break
            val dt = (sec - t).coerceIn(0f, 0.05f)
            t = sec
            if (particles.isEmpty() && size.width > 0f) seed(e, particles, size.width, size.height)
            step(e, particles, dt, sec)
        }
        g.engine.extras.clearEffect()
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    Canvas(Modifier.fillMaxSize().onSizeChanged { size = it.toSize() }.clickable { g.engine.extras.clearEffect() }) {
        val time = t   // read once: one redraw per frame
        val fade = ((DURATION - time) / 0.6f).coerceIn(0f, 1f)
        when (e) {
            MessageEffect.LASERS -> {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                for (i in 0 until 6) {
                    val a = time * 2.2f + i * 1.047f
                    val end = Offset(c.x + cos(a) * this.size.width, c.y + sin(a) * this.size.width)
                    drawLine(Palette[i % Palette.size].copy(alpha = 0.35f * fade), c, end, strokeWidth = 14f)
                    drawLine(Color.White.copy(alpha = 0.8f * fade), c, end, strokeWidth = 3f)
                }
            }
            else -> for (p in particles) {
                if (time < p.delay) continue
                if (e.emojiParticles) {
                    paint.textSize = p.size
                    paint.alpha = (255 * fade).toInt()
                    drawIntoCanvas { it.nativeCanvas.drawText(e.emoji, p.x, p.y, paint) }
                } else rotate(p.rot, Offset(p.x, p.y)) {
                    drawRect(p.color.copy(alpha = fade), topLeft = Offset(p.x - p.size / 2, p.y - p.size / 4), size = androidx.compose.ui.geometry.Size(p.size, p.size / 2))
                }
            }
        }
    }
}

private const val DURATION = 3.2f

private fun seed(e: MessageEffect, out: MutableList<Particle>, w: Float, h: Float) {
    val r = Random(System.nanoTime())
    fun col() = Palette[r.nextInt(Palette.size)]
    when (e) {
        MessageEffect.CONFETTI -> repeat(60) { out += Particle(r.nextFloat() * w, -r.nextFloat() * h * 0.5f, r.nextFloat() * 60 - 30, 160f + r.nextFloat() * 160, 14f + r.nextFloat() * 10, col(), r.nextFloat() * 360, r.nextFloat() * 400 - 200, 0f) }
        MessageEffect.SNOW -> repeat(40) { out += Particle(r.nextFloat() * w, -r.nextFloat() * h, r.nextFloat() * 30 - 15, 50f + r.nextFloat() * 70, 18f + r.nextFloat() * 14, Color.White, 0f, 0f, 0f) }
        MessageEffect.HEARTS, MessageEffect.BALLOONS -> repeat(if (e == MessageEffect.HEARTS) 28 else 14) {
            out += Particle(r.nextFloat() * w, h + r.nextFloat() * h * 0.6f, r.nextFloat() * 30 - 15, -(110f + r.nextFloat() * 120), if (e == MessageEffect.HEARTS) 26f + r.nextFloat() * 18 else 42f + r.nextFloat() * 18, Color.White, 0f, 0f, r.nextFloat() * 0.8f)
        }
        MessageEffect.FIREWORKS, MessageEffect.SPARKLES -> repeat(3) { burst ->
            val cx = w * (0.25f + 0.25f * burst); val cy = h * (0.3f + r.nextFloat() * 0.3f)
            repeat(18) { i ->
                val a = i / 18f * 6.283f; val sp = 120f + r.nextFloat() * 90
                out += Particle(cx, cy, cos(a) * sp, sin(a) * sp, if (e == MessageEffect.SPARKLES) 22f else 12f, col(), 0f, 0f, burst * 0.6f)
            }
        }
        MessageEffect.FIRE -> repeat(30) { out += Particle(r.nextFloat() * w, h + r.nextFloat() * 40, r.nextFloat() * 20 - 10, -(150f + r.nextFloat() * 160), 24f + r.nextFloat() * 20, Color.White, 0f, 0f, r.nextFloat() * 1.2f) }
        MessageEffect.LASERS -> Unit
    }
}

private fun step(e: MessageEffect, ps: List<Particle>, dt: Float, time: Float) {
    for (p in ps) {
        if (time < p.delay) continue
        p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.spin * dt
        when (e) {
            MessageEffect.FIREWORKS, MessageEffect.SPARKLES -> { p.vy += 90f * dt; p.vx *= 0.985f }
            MessageEffect.SNOW -> p.vx = sin(time * 2 + p.size) * 20f
            MessageEffect.HEARTS, MessageEffect.BALLOONS -> p.vx = sin(time * 2.5f + p.size) * 25f
            else -> Unit
        }
    }
}
