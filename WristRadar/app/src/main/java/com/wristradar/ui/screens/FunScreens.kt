package com.wristradar.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Text
import com.wristradar.WristRadarApp
import com.wristradar.core.GameKind
import com.wristradar.core.GamePhase
import com.wristradar.core.GameState
import com.wristradar.core.Phase
import com.wristradar.data.ChatMessage
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.rememberTextInput
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.ui.theme.WrType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ------------------------------------------------------------------------------- hub

@Composable
fun FunMenuScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val conn by g.engine.connection.collectAsState()
    val online = conn.phase == Phase.CONNECTED
    val msgs by g.messages.messages.collectAsState(initial = null)
    val streak = msgs?.let { streakDays(it) } ?: 0
    WrScreen {
        item { SectionLabel("Fun & extras") }
        item { Text("🔥 $streak day streak", fontSize = 14.sp, color = LocalPalette.current.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        if (!online) item { Hint("Most of these need your friend connected.") }
        item { WrChip("Draw & send", { nav.navigate("draw") }, secondary = "Doodle a picture", icon = { Text("✏️", fontSize = 15.sp) }) }
        item { WrChip("Emoji blast", { nav.navigate("blast") }, secondary = "Fill their screen", icon = { Text("💥", fontSize = 15.sp) }) }
        item { WrChip("Games", { nav.navigate("games") }, secondary = "Rock paper scissors, tic-tac-toe, reaction duel", icon = { Text("🎮", fontSize = 15.sp) }) }
        item { WrChip("Tap code", { nav.navigate("taps") }, secondary = "Silent vibration messages", icon = { Text("📳", fontSize = 15.sp) }) }
        item { WrChip("Hide & seek", { nav.navigate("hideseek") }, secondary = "Warmer or colder", icon = { Text("🌡", fontSize = 15.sp) }) }
        item { WrChip("Shared to-do list", { nav.navigate("todo") }, icon = { Text("📝", fontSize = 15.sp) }) }
        item { WrChip("My status", { nav.navigate("mood") }, secondary = g.engine.extras.myMood ?: "Set a mood", icon = { Text("😎", fontSize = 15.sp) }) }
    }
}

/** Days in a row where you both sent at least one message (today counts once both have). */
fun streakDays(list: List<ChatMessage>): Int {
    val zone = ZoneId.systemDefault()
    fun day(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    val real = list.filter { !it.isAlert && !it.isInfo }
    val mine = real.filter { it.outgoing }.map { day(it.localTime) }.toSet()
    val theirs = real.filter { !it.outgoing }.map { day(it.localTime) }.toSet()
    val both = mine intersect theirs
    var d = LocalDate.now()
    if (d !in both) d = d.minusDays(1)
    var n = 0
    while (d in both) { n++; d = d.minusDays(1) }
    return n
}

// ------------------------------------------------------------------------------- draw

private data class DrawStroke(val color: Color, val width: Float, val points: List<Offset>)

@Composable
fun DrawScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<DrawStroke>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val colors = listOf(Color.White, Color(0xFFFF4D5E), Color(0xFFFFC857), Color(0xFF39FF88), Color(0xFF2E86FF), Color(0xFFB266FF))
    var color by remember { mutableStateOf(colors[1]) }
    var thick by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var sending by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { current = listOf(it) },
                    onDrag = { c, _ -> current = current + c.position },
                    onDragEnd = { if (current.size > 1) strokes.add(DrawStroke(color, if (thick) 14f else 6f, current)); current = emptyList() },
                    onDragCancel = { current = emptyList() },
                )
            }.pointerInput(Unit) {
                detectTapGestures { o -> strokes.add(DrawStroke(color, if (thick) 14f else 6f, listOf(o, o + Offset(0.5f, 0.5f)))) }
            }
        ) {
            size = this.size
            (strokes + DrawStroke(color, if (thick) 14f else 6f, current)).forEach { s ->
                if (s.points.size < 2) return@forEach
                val path = Path().apply { moveTo(s.points[0].x, s.points[0].y); s.points.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, s.color, style = Stroke(s.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // Tools along the bottom edge.
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                colors.forEach { c ->
                    Box(Modifier.size(16.dp).clip(CircleShape).background(c).border(if (c == color) 2.dp else 0.dp, Color.Gray, CircleShape).clickable { color = c })
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Tool(if (thick) "▬" else "─") { thick = !thick }
                Tool("↶") { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
                Tool("🗑") { strokes.clear() }
                Tool(if (sending) "…" else "Send", highlight = true) {
                    if (sending || strokes.isEmpty() || size.width <= 0f) return@Tool
                    sending = true
                    val bmp = renderDoodle(strokes.toList(), size.width, size.height)
                    scope.launch { g.engine.messenger.sendImage(bmp); nav.popBackStack() }
                }
            }
        }
        Text("Draw with your finger", fontSize = 9.sp, color = p.textDim, modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
    }
}

@Composable
private fun Tool(label: String, highlight: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        label, fontSize = 12.sp, color = if (highlight) p.onColor(p.primary) else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (highlight) p.primary else Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun renderDoodle(strokes: List<DrawStroke>, w: Float, h: Float): Bitmap {
    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(android.graphics.Color.BLACK)
    val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    strokes.forEach { s ->
        paint.color = s.color.toArgb(); paint.strokeWidth = s.width
        val path = android.graphics.Path()
        path.moveTo(s.points[0].x, s.points[0].y)
        s.points.drop(1).forEach { path.lineTo(it.x, it.y) }
        c.drawPath(path, paint)
    }
    return bmp
}

// ------------------------------------------------------------------------------- emoji blast

val BlastEmojis = listOf("😂", "💀", "🔥", "❤️", "😭", "🤣", "😡", "👀", "🥶", "🤯", "💯", "🫡", "🤡", "😈", "🎉", "👻")

@Composable
fun BlastScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    var note by remember { mutableStateOf<String?>(null) }
    WrScreen {
        item { SectionLabel("Emoji blast") }
        item { Hint("Fills your friend's screen and buzzes their watch.") }
        BlastEmojis.chunked(4).forEachIndexed { i, row ->
            item(key = "b$i") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { e ->
                        Text(e, fontSize = 24.sp, modifier = Modifier.clip(CircleShape).background(p.surface).clickable {
                            note = if (g.engine.extras.sendBlast(e)) "$e sent!" else "Not connected"
                        }.padding(6.dp))
                    }
                }
            }
        }
        note?.let { item { Hint(it) } }
    }
}

/** Full-screen emoji rain when the partner sends a blast. Tap to close. */
@Composable
fun BlastOverlay() {
    val g = WristRadarApp.graph
    val e by g.engine.extras.blast.collectAsState()
    val emoji = e ?: return
    val t = rememberInfiniteTransition(label = "blast")
    val s by t.animateFloat(0.8f, 1.25f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "s")
    LaunchedEffect(emoji) { delay(3500); g.engine.extras.clearBlast() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).clickable { g.engine.extras.clearBlast() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 80.sp, modifier = Modifier.graphicsLayer { scaleX = s; scaleY = s })
            Text("${g.profile.partnerName.value ?: "Your friend"} sent this", fontSize = 11.sp, color = Color.White)
        }
    }
}

// ------------------------------------------------------------------------------- games

@Composable
fun GamesScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val game by g.engine.extras.game.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    WrScreen {
        item { SectionLabel("Games") }
        game?.let { gs -> item { WrChip("Back to ${gs.kind.title}", { nav.navigate("game") }, primary = true) } }
        GameKind.entries.forEach { k ->
            item(key = k.name) {
                val sc = g.engine.extras.score(k)
                val partnerCode = g.engine.updater.state.collectAsState().value.partner?.versionCode
                val tooOld = partnerCode != null && partnerCode < k.since
                WrChip(k.title, {
                    if (tooOld) note = "Your friend needs to update to play ${k.title}. Settings › Updates › Send to friend."
                    else if (g.engine.extras.invite(k)) nav.navigate("game") else note = "Connect to your friend first"
                }, secondary = if (tooOld) "🔒 Friend needs to update" else "W ${sc.wins} · L ${sc.losses} · D ${sc.draws}")
            }
        }
        note?.let { item { Hint(it) } }
    }
}

@Composable
fun GameScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val gs by g.engine.extras.game.collectAsState()
    val name = g.profile.partnerName.value ?: "Friend"
    val s = gs
    if (s == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.kind.title.uppercase(), style = WrType.label, color = p.primary)
            Spacer(Modifier.height(4.dp))
            when (s.phase) {
                GamePhase.INVITED_ME -> {
                    Text("$name wants to play", fontSize = 13.sp, color = p.text)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tool("Play", highlight = true) { g.engine.extras.accept() }
                        Tool("No") { g.engine.extras.quit(); nav.popBackStack() }
                    }
                }
                GamePhase.WAITING_ACCEPT -> Text("Waiting for $name…", fontSize = 13.sp, color = p.textDim)
                else -> when (s.kind) {
                    GameKind.RPS -> RpsBody(s, name)
                    GameKind.TTT -> TttBody(s, name)
                    GameKind.DUEL -> DuelBody(s, name)
                    GameKind.C4 -> C4Body(s, name)
                    GameKind.MEMORY -> MemoryBody(s, name)
                    GameKind.DICE -> DiceBody(s, name)
                }
            }
            if (s.phase == GamePhase.DONE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when (s.result) { 1 -> "🏆 You win!"; -1 -> "$name wins"; else -> "Draw" },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (s.result == 1) p.connection else p.text,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tool("Again", highlight = true) { g.engine.extras.invite(s.kind) }
                    Tool("Done") { g.engine.extras.quit(); nav.popBackStack() }
                }
            }
        }
    }
}

private val RpsIcons = listOf("✊", "✋", "✌️")

@Composable
private fun RpsBody(s: GameState, name: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    if (s.phase == GamePhase.DONE) {
        Text("You ${RpsIcons[s.mine ?: 0]}  vs  ${RpsIcons[s.theirs ?: 0]} $name", fontSize = 16.sp, color = p.text)
        return
    }
    Text(if (s.mine == null) "Pick one" else "Waiting for $name…", fontSize = 12.sp, color = p.textDim)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RpsIcons.forEachIndexed { i, e ->
            Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape)
                .background(if (s.mine == i) p.primary.copy(alpha = 0.5f) else p.surface)
                .clickable(enabled = s.mine == null) { g.engine.extras.move(i) }.padding(8.dp))
        }
    }
}

@Composable
private fun TttBody(s: GameState, name: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    if (s.phase != GamePhase.DONE) Text(if (s.myTurn) "Your turn (${if (s.myMark == 1) "X" else "O"})" else "$name's turn", fontSize = 11.sp, color = p.textDim)
    Spacer(Modifier.height(4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (r in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (c in 0..2) {
                val i = r * 3 + c
                val v = s.board[i]
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(p.surface)
                        .clickable(enabled = s.phase == GamePhase.PLAYING && s.myTurn && v == 0) { g.engine.extras.move(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(when (v) { 1 -> "X"; 2 -> "O"; else -> "" }, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = if (v == s.myMark) p.primary else p.secondary)
                }
            }
        }
    }
}

@Composable
private fun DuelBody(s: GameState, name: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    when (s.phase) {
        GamePhase.PLAYING -> Text("Get ready…", fontSize = 14.sp, color = p.textDim)
        GamePhase.COUNTDOWN, GamePhase.GO -> {
            val go = s.phase == GamePhase.GO
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(if (go) Color(0xFF2ECC71) else Color(0xFFE74C3C))
                    .pointerInput(s.id) { detectTapGestures(onPress = { g.engine.extras.move(0) }) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when { s.mine == 9999 -> "Too early!"; s.mine != null -> "${s.mine} ms"; go -> "TAP!"; else -> "Wait…" },
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
            if (s.mine != null) Text("Waiting for $name…", fontSize = 11.sp, color = p.textDim)
        }
        GamePhase.DONE -> {
            fun fmt(v: Int?) = when (v) { null -> "?"; 9999 -> "too early"; else -> "$v ms" }
            Text("You: ${fmt(s.mine)}", fontSize = 13.sp, color = p.text)
            Text("$name: ${fmt(s.theirs)}", fontSize = 13.sp, color = p.text)
        }
        else -> Unit
    }
}

/** Small card when the partner invites you to a game while you're elsewhere in the app. */
@Composable
fun GameInviteOverlay(onOpen: () -> Unit) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val gs by g.engine.extras.game.collectAsState()
    var dismissedId by remember { mutableStateOf(0) }
    val s = gs ?: return
    if (s.phase != GamePhase.INVITED_ME || s.id == dismissedId) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = 24.dp).clip(RoundedCornerShape(16.dp)).background(p.surface.copy(alpha = 0.97f))
                .border(1.dp, p.primary, RoundedCornerShape(16.dp)).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🎮 ${s.kind.title}", fontSize = 12.sp, color = p.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Tool("Play", highlight = true) { g.engine.extras.accept(); dismissedId = s.id; onOpen() }
                Tool("No") { g.engine.extras.quit(); dismissedId = s.id }
            }
        }
    }
}

// ------------------------------------------------------------------------------- tap code

@Composable
fun TapCodeScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val pattern = remember { mutableStateListOf<Long>() }
    var downAt by remember { mutableStateOf(0L) }
    var upAt by remember { mutableStateOf(0L) }
    var pressed by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TAP CODE", style = WrType.label, color = p.primary)
            Text("Short tap = dot · hold = dash", fontSize = 9.sp, color = p.textDim)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(if (pressed) p.primary else p.surface)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            val now = System.currentTimeMillis()
                            if (upAt != 0L && pattern.size < 59) pattern.add((now - upAt).coerceIn(80, 1500))
                            downAt = now; pressed = true
                            tryAwaitRelease()
                            pressed = false
                            upAt = System.currentTimeMillis()
                            if (pattern.size < 59) pattern.add((upAt - downAt).coerceIn(40, 1500))
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Text("${(pattern.size + 1) / 2}", fontSize = 22.sp, color = if (pressed) p.onColor(p.primary) else p.text) }
            Spacer(Modifier.height(6.dp))
            Text(pattern.chunked(2).joinToString(" ") { if (it[0] > 250) "—" else "•" }.takeLast(24), fontSize = 12.sp, color = p.text)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tool("Clear") { pattern.clear(); upAt = 0L }
                Tool("Send", highlight = true) {
                    note = if (g.engine.extras.sendTaps(pattern.toList())) "Sent!" else "Not connected"
                    pattern.clear(); upAt = 0L
                }
            }
            note?.let { Text(it, fontSize = 10.sp, color = p.textDim) }
        }
    }
}

// ------------------------------------------------------------------------------- hide & seek

@Composable
fun HideSeekScreen() {
    val g = WristRadarApp.graph
    val conn by g.engine.connection.collectAsState()
    val rssi = conn.rssi?.takeIf { conn.phase == Phase.CONNECTED }
    val heat = rssi?.let { ((it + 95) / 50f).coerceIn(0f, 1f) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(heat) {
        // Buzzes faster the closer you get.
        val vib = ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        while (heat != null) {
            delay((2600 - heat * 2200).toLong())
            runCatching { vib?.vibrate(android.os.VibrationEffect.createOneShot((30 + heat * 70).toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE)) }
        }
    }
    val bg = heat?.let { lerp(Color(0xFF1E5BFF), Color(0xFFFF2D2D), it) } ?: Color.DarkGray
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    heat == null -> "Not connected"
                    heat > 0.85f -> "🔥 BURNING"
                    heat > 0.65f -> "♨️ Hot"
                    heat > 0.45f -> "🙂 Warm"
                    heat > 0.25f -> "🥶 Cold"
                    else -> "🧊 Freezing"
                },
                fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White,
            )
            Text("Hide & seek", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

// ------------------------------------------------------------------------------- to-do

@Composable
fun TodoScreen() {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val todos by g.engine.extras.todos.collectAsState()
    val input = rememberTextInput { g.engine.extras.addTodo(it) }
    WrScreen {
        item { SectionLabel("Shared to-do") }
        item { WrChip("+ Add", { input("New item") }, primary = true) }
        if (todos.isEmpty()) item { Hint("Plans, reminders, things to bring. Both watches see the same list.") }
        todos.forEach { t ->
            item(key = t.id) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface)
                        .clickable { g.engine.extras.toggleTodo(t.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (t.done) "☑" else "☐", fontSize = 15.sp, color = p.primary)
                    Text(t.text, fontSize = 13.sp, color = if (t.done) p.textDim else p.text, modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    Text("✕", fontSize = 12.sp, color = p.textDim, modifier = Modifier.clickable { g.engine.extras.deleteTodo(t.id) }.padding(4.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- mood

val Moods = listOf("😎 Chilling", "📚 In class", "🎮 Gaming", "😴 Asleep", "🍔 Eating", "🚶 On my way", "🏠 Home", "⚽ Sport", "🎧 Music", "🔕 Busy", "😤 Bored", "🥳 Hyped")

@Composable
fun MoodScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    var current by remember { mutableStateOf(g.engine.extras.myMood) }
    val custom = rememberTextInput { t -> val m = t.trim().take(40); g.engine.extras.setMood(m); current = m }
    WrScreen {
        item { SectionLabel("My status") }
        item { Hint(current?.let { "Now: $it" } ?: "Your friend sees this on their watch.") }
        Moods.forEach { m ->
            item(key = m) { WrChip(m, { g.engine.extras.setMood(m); current = m; nav.popBackStack() }, primary = m == current) }
        }
        item { WrChip("Write my own", { custom("Your status") }) }
        if (current != null) item { WrChip("Clear status", { g.engine.extras.setMood(null); current = null }) }
    }
}
