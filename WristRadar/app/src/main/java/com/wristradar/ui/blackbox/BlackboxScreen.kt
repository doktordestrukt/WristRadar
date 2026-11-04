package com.wristradar.ui.blackbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.data.AnimationLevel
import com.wristradar.security.BlackboxAlert
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.sin
import kotlin.random.Random

private val Blood = Color(0xFFFF2A2A)
private val Term = Color(0xFF39FF14)
private val Bone = Color(0xFFE9E3D2)
private val Cyan = Color(0xFF00F0FF)

/** Full-screen BLACKBOX alert layered above the whole app while an alert is active. */
@Composable
fun BlackboxOverlay(onOpenLog: () -> Unit) {
    val g = WristRadarApp.graph
    val alert by g.blackbox.alert.collectAsState()
    val a = alert ?: return
    val settings = g.settings.state.collectAsState().value
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Audio: glitch2, then looping hacker music; paused while the watch screen is off / app hidden.
    DisposableEffect(a.time) {
        val audio = BlackboxAudio(ctx)
        audio.start(sound = settings.blackboxSound, withMusic = settings.blackboxMusic)
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> audio.pause()
                Lifecycle.Event.ON_START -> audio.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); audio.stop(outro = settings.blackboxSound) }
    }
    BackHandler { g.blackbox.dismiss() }
    BlackboxScene(a, settings.animations, onDismiss = { g.blackbox.dismiss() }, onLog = { g.blackbox.dismiss(); onOpenLog() })
}

@Composable
private fun BlackboxScene(a: BlackboxAlert, anim: AnimationLevel, onDismiss: () -> Unit, onLog: () -> Unit) {
    val animate = anim != AnimationLevel.OFF
    val t = rememberInfiniteTransition(label = "bb")
    val laugh by t.animateFloat(0f, 1f, infiniteRepeatable(tween(170, easing = LinearEasing), RepeatMode.Reverse), label = "jaw")
    val flicker by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(90), RepeatMode.Reverse), label = "eye")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "scan")

    // Glitch bursts: heavy for the first ~1.5 s, then a short burst every few seconds.
    var glitch by remember { mutableStateOf(if (animate) 1f else 0f) }
    var seed by remember { mutableIntStateOf(0) }
    LaunchedEffect(a.time, animate) {
        if (!animate) { glitch = 0f; return@LaunchedEffect }
        val start = System.currentTimeMillis()
        while (true) {
            val age = System.currentTimeMillis() - start
            glitch = when {
                age < 1500 -> 1f
                (age / 120) % 28 < 2 -> 0.7f
                else -> 0f
            }
            seed++
            delay(60)
        }
    }
    val rnd = remember(seed) { Random(seed) }
    val jitterX = if (glitch > 0f) (rnd.nextFloat() - 0.5f) * 14f * glitch else 0f

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }, // block touches to the app below
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp)
                .offset(x = jitterX.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Skull(
                size = 96.dp.value,
                jaw = if (animate) laugh else 0.5f,
                eye = if (animate) flicker else 1f,
                shake = if (animate && anim == AnimationLevel.FULL) sin(laugh * Math.PI.toFloat()) else 0f,
            )
            Spacer(Modifier.height(4.dp))
            GlitchText(
                "PLEASE DO NOT DECRYPT THIS", glitch, rnd,
                TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Blood,
                    shadow = Shadow(Blood, Offset.Zero, 16f), textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(3.dp))
            val header = when {
                a.preview -> "BLACKBOX // PREVIEW · NO REAL EVENT"
                a.deniedHere -> "BLACKBOX // ACCESS DENIED"
                else -> "BLACKBOX // UNAUTHORISED ACCESS DETECTED"
            }
            GlitchText(
                header, glitch * 0.6f, rnd,
                TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Term,
                    letterSpacing = 1.sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(8.dp))
            val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(a.time))
            val lines = buildList {
                add("> EVENT: " + (a.type?.title ?: a.detail))
                if (a.type != null) add("> DETAIL: ${a.detail}")
                add("> DEVICE: ${a.deviceName}")
                a.deviceId?.let { add("> BT ID: $it (rotating)") }
                add("> TIME: $time")
                if (a.deniedHere) add("> The target watch logged this attempt.")
                else if (!a.preview) add("> Logged locally. No message content was exposed.")
                if (a.repeats > 0) add("> +${a.repeats} further attempt(s) logged")
            }
            Typewriter(lines, animate)
            Spacer(Modifier.height(10.dp))
            TermButton("DISMISS", Blood, onDismiss)
            if (!a.preview) { Spacer(Modifier.height(6.dp)); TermButton("SECURITY LOG", Term, onLog) }
            Spacer(Modifier.height(24.dp))
        }
        // CRT scanlines + moving scan bar + glitch noise bars
        Canvas(Modifier.fillMaxSize()) {
            val step = 3.dp.toPx()
            var y = 0f
            while (y < size.height) { drawLine(Color.Black.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
            if (animate) {
                val by = size.height * sweep
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Term.copy(alpha = 0.10f), Color.Transparent), by - 30f, by + 30f),
                    Offset(0f, by - 30f), Size(size.width, 60f))
            }
            if (glitch > 0f) {
                repeat((6 * glitch).toInt() + 1) {
                    val h = rnd.nextFloat() * 10.dp.toPx() + 2f
                    val top = rnd.nextFloat() * size.height
                    val c = if (rnd.nextBoolean()) Blood else Cyan
                    drawRect(c.copy(alpha = 0.18f * glitch), Offset(rnd.nextFloat() * size.width * 0.3f, top), Size(size.width * (0.4f + rnd.nextFloat() * 0.6f), h))
                }
            }
        }
    }
}

@Composable
private fun GlitchText(text: String, glitch: Float, rnd: Random, style: TextStyle) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (glitch > 0f) {
            val dx = (rnd.nextFloat() * 3f + 1f) * glitch
            Text(text, style = style.copy(color = Cyan.copy(alpha = 0.7f), shadow = null), modifier = Modifier.offset(x = (-dx).dp))
            Text(text, style = style.copy(color = Blood.copy(alpha = 0.7f), shadow = null), modifier = Modifier.offset(x = dx.dp))
        }
        Text(scramble(text, glitch, rnd), style = style)
    }
}

private const val NOISE = "#%&@$*!?/\\<>01"
private fun scramble(s: String, glitch: Float, rnd: Random): String =
    if (glitch < 0.5f) s else s.map { if (it != ' ' && rnd.nextFloat() < 0.12f * glitch) NOISE[rnd.nextInt(NOISE.length)] else it }.joinToString("")

@Composable
private fun Typewriter(lines: List<String>, animate: Boolean) {
    val full = lines.joinToString("\n")
    var shown by remember(full) { mutableIntStateOf(if (animate) 0 else full.length) }
    LaunchedEffect(full, animate) {
        if (!animate) { shown = full.length; return@LaunchedEffect }
        delay(900)
        while (shown < full.length) { shown++; delay(14) }
    }
    val cursor = if (shown < full.length) "█" else ""
    Text(
        full.take(shown) + cursor,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Term, lineHeight = 12.sp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TermButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(17.dp))
            .border(1.dp, color, RoundedCornerShape(17.dp)).background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = color) }
}

/** Original laughing skull, drawn in code: rattling jaw, flickering red eyes, head shake. */
@Composable
private fun Skull(size: Float, jaw: Float, eye: Float, shake: Float) {
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(Brush.radialGradient(listOf(Blood.copy(alpha = 0.35f * eye), Color.Transparent), center, w * 0.55f), w * 0.55f, center)
        rotate(shake * 7f, Offset(w / 2, h * 0.75f)) {
            drawSkull(w, h, jaw, eye)
        }
    }
}

private fun DrawScope.drawSkull(w: Float, h: Float, jaw: Float, eye: Float) {
    val cx = w / 2
    // cranium
    drawOval(Bone, Offset(w * 0.14f, h * 0.04f), Size(w * 0.72f, h * 0.62f))
    // cheekbones / upper jaw block
    drawRoundRect(Bone, Offset(w * 0.25f, h * 0.48f), Size(w * 0.5f, h * 0.2f), CornerRadius(w * 0.08f))
    // cracks
    drawLine(Color(0xFF8C8577), Offset(cx + w * 0.05f, h * 0.06f), Offset(cx + w * 0.11f, h * 0.18f), 2f)
    drawLine(Color(0xFF8C8577), Offset(cx + w * 0.11f, h * 0.18f), Offset(cx + w * 0.07f, h * 0.26f), 2f)
    // eye sockets
    val eyeW = w * 0.2f; val eyeH = h * 0.2f
    listOf(cx - w * 0.2f, cx + w * 0.2f).forEach { ex ->
        drawOval(Color.Black, Offset(ex - eyeW / 2, h * 0.30f), Size(eyeW, eyeH))
        drawCircle(Blood.copy(alpha = 0.35f * eye), w * 0.075f, Offset(ex, h * 0.40f))
        drawCircle(Blood.copy(alpha = eye), w * 0.035f, Offset(ex, h * 0.40f))
    }
    // nose
    val nose = Path().apply {
        moveTo(cx, h * 0.48f); lineTo(cx - w * 0.055f, h * 0.58f); lineTo(cx + w * 0.055f, h * 0.58f); close()
    }
    drawPath(nose, Color.Black)
    // upper teeth
    val teethTop = h * 0.62f
    drawRect(Bone, Offset(w * 0.3f, teethTop), Size(w * 0.4f, h * 0.07f))
    for (i in 0..6) {
        val x = w * 0.3f + i * w * 0.4f / 7
        drawLine(Color(0xFF3A342A), Offset(x, teethTop), Offset(x, teethTop + h * 0.07f), 1.5f)
    }
    // lower jaw drops open and shut = laughing
    val drop = h * 0.13f * jaw
    translate(top = drop) {
        drawRect(Color.Black, Offset(w * 0.3f, teethTop + h * 0.07f - drop), Size(w * 0.4f, drop.coerceAtLeast(0f)))
        drawRoundRect(Bone, Offset(w * 0.28f, teethTop + h * 0.07f), Size(w * 0.44f, h * 0.16f), CornerRadius(w * 0.1f))
        for (i in 0..6) {
            val x = w * 0.3f + i * w * 0.4f / 7
            drawLine(Color(0xFF3A342A), Offset(x, teethTop + h * 0.07f), Offset(x, teethTop + h * 0.13f), 1.5f)
        }
    }
}
