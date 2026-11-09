package com.wristradar.core

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import com.wristradar.AppGraph
import com.wristradar.crypto.Crypto
import com.wristradar.protocol.A
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

// ------------------------------------------------------------------------------- models

enum class GameKind(val id: Int, val title: String) {
    RPS(1, "Rock Paper Scissors"), TTT(2, "Tic-Tac-Toe"), DUEL(3, "Reaction Duel"),
    C4(4, "Connect 4"), MEMORY(5, "Memory Match"), DICE(6, "Dice Duel");
    /** First app version (versionCode) that knows this game. */
    val since: Int get() = if (id >= 4) 13 else 0
    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } }
}

/** Pure game rules, shared by both watches (and the unit tests). */
object GameRules {
    const val C4_COLS = 7
    const val C4_ROWS = 6
    const val MEM_CARDS = 12
    val MEM_FACES = listOf("🍓", "🌸", "💎", "🦄", "🍩", "🌙")

    fun emptyBoard(kind: GameKind) = List(when (kind) { GameKind.C4 -> C4_COLS * C4_ROWS; GameKind.MEMORY -> MEM_CARDS; else -> 9 }) { 0 }

    /** Row-major, row 0 at the top. Returns the cell a disc lands in, or -1 if the column is full. */
    fun c4Drop(board: List<Int>, col: Int): Int {
        if (col !in 0 until C4_COLS) return -1
        for (row in C4_ROWS - 1 downTo 0) if (board[row * C4_COLS + col] == 0) return row * C4_COLS + col
        return -1
    }

    fun c4Winner(b: List<Int>): Int {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for (r in 0 until C4_ROWS) for (c in 0 until C4_COLS) {
            val v = b[r * C4_COLS + c]; if (v == 0) continue
            for ((dr, dc) in dirs) {
                var k = 1
                while (k < 4) {
                    val rr = r + dr * k; val cc = c + dc * k
                    if (rr !in 0 until C4_ROWS || cc !in 0 until C4_COLS || b[rr * C4_COLS + cc] != v) break
                    k++
                }
                if (k == 4) return v
            }
        }
        return 0
    }

    /** Same shuffled deck on both watches, from the game id. Each value is a face index, twice. */
    fun memDeck(seed: Int): List<Int> = (0 until MEM_CARDS).map { it / 2 }.shuffled(java.util.Random(seed.toLong()))

    /** Dice duel: two dice per player packed as d1*10+d2 (both 1..6). */
    fun diceSum(v: Int) = v / 10 + v % 10
    fun validDice(v: Int) = v / 10 in 1..6 && v % 10 in 1..6

    /**
     * Memory: flip one card. Two face-up cards that match are kept by the player (board = their
     * mark) and they go again; a mismatch stays visible until the next flip, then the turn passes.
     */
    fun memFlip(gs: GameState, card: Int, byStarter: Boolean): GameState? {
        if (card !in 0 until MEM_CARDS || gs.board.size != MEM_CARDS || gs.board[card] != 0) return null
        val deck = memDeck(gs.id)
        val shown = if (gs.flips.size >= 2) emptyList() else gs.flips
        if (card in shown) return null
        val flips = shown + card
        if (flips.size < 2) return gs.copy(flips = flips)
        val mark = if (byStarter) 1 else 2
        return if (deck[flips[0]] == deck[flips[1]]) {
            gs.copy(board = gs.board.toMutableList().also { it[flips[0]] = mark; it[flips[1]] = mark }, flips = emptyList())
        } else gs.copy(flips = flips, starterTurn = !gs.starterTurn)
    }
}

/** Full-screen effects that can be sent with a message. */
enum class MessageEffect(val id: Int, val emoji: String, val title: String, val emojiParticles: Boolean) {
    CONFETTI(1, "🎉", "Confetti", false),
    HEARTS(2, "❤️", "Hearts", true),
    FIREWORKS(3, "🎆", "Fireworks", false),
    BALLOONS(4, "🎈", "Balloons", true),
    LASERS(5, "🔦", "Lasers", false),
    SNOW(6, "❄️", "Snow", true),
    SPARKLES(7, "✨", "Sparkles", true),
    FIRE(8, "🔥", "Fire", true);
    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } }
}

enum class GamePhase { INVITED_ME, WAITING_ACCEPT, PLAYING, COUNTDOWN, GO, DONE }

data class GameState(
    val kind: GameKind,
    val id: Int,
    val iStarted: Boolean,
    val phase: GamePhase,
    /** RPS: 0 rock, 1 paper, 2 scissors. DUEL: reaction ms. */
    val mine: Int? = null,
    val theirs: Int? = null,
    /** TTT: 9 cells, 0 empty, 1 = X (starter), 2 = O. */
    val board: List<Int> = List(9) { 0 },
    val goAt: Long = 0,
    /** 1 I won, -1 they won, 0 draw, null not finished. */
    val result: Int? = null,
    /** MEMORY: whose turn (true = the starter), and the cards face-up right now. */
    val starterTurn: Boolean = true,
    val flips: List<Int> = emptyList(),
) {
    val myMark get() = if (iStarted) 1 else 2
    val myTurn: Boolean get() {
        if (kind == GameKind.MEMORY) return starterTurn == iStarted
        val placed = board.count { it != 0 }
        return (placed % 2 == 0) == iStarted
    }
}

data class TodoItem(val id: String, val text: String, val done: Boolean, val byMe: Boolean)

data class Score(val wins: Int = 0, val losses: Int = 0, val draws: Int = 0)

/**
 * Two-person extras over the encrypted session: emoji blasts, games, tap code (vibration),
 * shared to-do list, mood status and the "leaving range" alert.
 */
class FunHub(private val ctx: Context, private val g: AppGraph, private val engine: RadarEngine) {

    // ---- overlays shown above every screen
    private val _blast = MutableStateFlow<String?>(null)
    val blast: StateFlow<String?> = _blast.asStateFlow()
    private val _game = MutableStateFlow<GameState?>(null)
    val game: StateFlow<GameState?> = _game.asStateFlow()

    private val _effect = MutableStateFlow<MessageEffect?>(null)
    val effect: StateFlow<MessageEffect?> = _effect.asStateFlow()

    private val _todos = MutableStateFlow<List<TodoItem>>(emptyList())
    val todos: StateFlow<List<TodoItem>> = _todos.asStateFlow()

    private val _partnerMood = MutableStateFlow<String?>(null)
    val partnerMood: StateFlow<String?> = _partnerMood.asStateFlow()

    private val prefs = ctx.getSharedPreferences("wr_fun", Context.MODE_PRIVATE)
    private val todoFile = File(ctx.filesDir, "todo.bin")
    private val pending = ConcurrentLinkedQueue<Pair<Byte, ByteArray>>()
    private var lastRangeAlert = 0L
    private var weak = false

    init {
        _partnerMood.value = prefs.getString("partner_mood", null)
        engine.scope.launch { loadTodos() }
    }

    val myMood: String? get() = prefs.getString("my_mood", null)

    // ------------------------------------------------------------------ sending helper

    private fun send(type: Byte, payload: ByteArray, queueIfOffline: Boolean = false) {
        val s = engine.currentSession()
        if (s == null) { if (queueIfOffline) pending.add(type to payload); return }
        engine.scope.launch { if (!s.send(type, payload) && queueIfOffline) pending.add(type to payload) }
    }

    suspend fun onSessionUp(s: SecureSession) {
        while (true) { val (t, p) = pending.poll() ?: break; s.send(t, p) }
        myMood?.let { s.send(A.MOOD, Out().str(it).build()) }
    }

    fun onSessionDown() {
        val gs = _game.value
        if (gs != null && gs.phase != GamePhase.DONE) _game.value = null
    }

    // ------------------------------------------------------------------ inbound

    suspend fun handle(type: Byte, p: ByteArray) {
        val r = In(p)
        when (type) {
            A.BLAST -> {
                val e = r.str(16).take(8)
                if (e.isNotBlank()) { _blast.value = e; buzz(longArrayOf(0, 80, 60, 80)); if (!g.uiVisibility.appVisible) g.notifier.showInfo(name(), "$e $e $e") }
            }
            A.TAPS -> {
                val n = r.i().coerceIn(0, 60)
                val pattern = LongArray(n + 1) { if (it == 0) 0L else r.i().toLong().coerceIn(20, 1500) }
                buzz(pattern)
                engine.messenger.insertInfo("📳 ${name()} sent you a tap code (${(n + 1) / 2} taps)")
            }
            A.MOOD -> {
                val m = r.str(60).trim().take(40).ifBlank { null }
                _partnerMood.value = m
                prefs.edit().putString("partner_mood", m).apply()
            }
            A.TODO -> onTodo(r)
            A.EFFECT -> MessageEffect.of(r.b().toInt())?.let { e ->
                if (g.settings.current.animations != com.wristradar.data.AnimationLevel.OFF) _effect.value = e
                buzz(longArrayOf(0, 40, 40, 40))
            }
            A.GAME -> onGame(r)
        }
    }

    private fun name() = g.profile.partnerName.value ?: "Your friend"

    // ------------------------------------------------------------------ emoji blast

    fun sendBlast(emoji: String): Boolean {
        if (engine.currentSession() == null) return false
        send(A.BLAST, Out().str(emoji).build())
        return true
    }

    fun clearBlast() { _blast.value = null }

    // ------------------------------------------------------------------ message effects

    /** Plays the effect here and sends it to the friend (only if connected - effects aren't queued). */
    fun sendEffect(e: MessageEffect) {
        previewEffect(e)
        send(A.EFFECT, Out().b(e.id.toByte()).build())
    }

    fun previewEffect(e: MessageEffect) {
        if (g.settings.current.animations != com.wristradar.data.AnimationLevel.OFF) _effect.value = e
    }

    fun clearEffect() { _effect.value = null }

    // ------------------------------------------------------------------ tap code

    /** pattern: alternating on/off durations in ms, starting with "on". */
    fun sendTaps(pattern: List<Long>): Boolean {
        if (pattern.isEmpty() || engine.currentSession() == null) return false
        val p = pattern.take(59)
        val out = Out().i(p.size)
        p.forEach { out.i(it.coerceIn(20, 1500).toInt()) }
        send(A.TAPS, out.build())
        return true
    }

    // ------------------------------------------------------------------ leaving range

    /** Called with the smoothed partner signal while connected. */
    fun onSignal(rssi: Int) {
        if (!g.settings.current.rangeAlert) return
        if (rssi > -84) weak = false
        if (rssi < -90 && !weak) {
            weak = true
            val now = System.currentTimeMillis()
            if (now - lastRangeAlert > 120_000) {
                lastRangeAlert = now
                buzz(longArrayOf(0, 200, 120, 200))
                g.notifier.showInfo("📡 ${name()} is leaving range", "Signal is getting weak - stay closer to keep chatting")
            }
        }
    }

    fun onLinkLost(connectedForMs: Long) {
        if (!g.settings.current.rangeAlert || connectedForMs < 30_000) return
        val now = System.currentTimeMillis()
        if (now - lastRangeAlert < 60_000) return
        lastRangeAlert = now
        buzz(longArrayOf(0, 300, 150, 300))
        g.notifier.showInfo("📡 Lost ${name()}", "Out of range - WristRadar keeps looking")
    }

    // ------------------------------------------------------------------ mood

    fun setMood(m: String?) {
        prefs.edit().putString("my_mood", m).apply()
        send(A.MOOD, Out().str(m ?: "").build(), queueIfOffline = true)
    }

    // ------------------------------------------------------------------ shared to-do list

    fun addTodo(text: String) {
        val t = text.trim().take(120); if (t.isEmpty() || _todos.value.size >= MAX_TODOS) return
        val id = UUID.randomUUID().toString()
        _todos.update { it + TodoItem(id, t, false, true) }
        saveTodos()
        send(A.TODO, Out().b(0).uuid(UUID.fromString(id)).str(t).build(), queueIfOffline = true)
    }

    fun toggleTodo(id: String) {
        val item = _todos.value.firstOrNull { it.id == id } ?: return
        _todos.update { l -> l.map { if (it.id == id) it.copy(done = !it.done) else it } }
        saveTodos()
        send(A.TODO, Out().b(1).uuid(UUID.fromString(id)).b(if (!item.done) 1 else 0).build(), queueIfOffline = true)
    }

    fun deleteTodo(id: String) {
        _todos.update { l -> l.filter { it.id != id } }
        saveTodos()
        send(A.TODO, Out().b(2).uuid(UUID.fromString(id)).build(), queueIfOffline = true)
    }

    private fun onTodo(r: In) {
        val op = r.b().toInt()
        val id = r.uuid().toString()
        when (op) {
            0 -> { val t = r.str(400).take(120); if (_todos.value.size < MAX_TODOS && _todos.value.none { it.id == id }) _todos.update { it + TodoItem(id, t, false, false) } }
            1 -> { val d = r.b().toInt() == 1; _todos.update { l -> l.map { if (it.id == id) it.copy(done = d) else it } } }
            2 -> _todos.update { l -> l.filter { it.id != id } }
        }
        saveTodos()
    }

    private suspend fun loadTodos() = withContext(Dispatchers.IO) {
        if (!todoFile.exists()) return@withContext
        val plain = g.vault.decrypt(todoFile.readBytes(), "todo") ?: return@withContext
        val arr = runCatching { JSONArray(plain.toString(Charsets.UTF_8)) }.getOrNull() ?: return@withContext
        _todos.value = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TodoItem(o.getString("id"), o.getString("t"), o.optBoolean("d"), o.optBoolean("m"))
        }
    }

    private fun saveTodos() {
        val list = _todos.value
        engine.scope.launch(Dispatchers.IO) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.text).put("d", it.done).put("m", it.byMe)) }
            todoFile.writeBytes(g.vault.encrypt(arr.toString().toByteArray(), "todo"))
        }
    }

    // ------------------------------------------------------------------ games
    //  GAME = game byte | op byte | id int | value int
    //  ops: 0 invite, 1 accept, 2 move, 3 quit, 4 start (duel: value = delay ms)

    fun score(kind: GameKind) = Score(prefs.getInt("w${kind.id}", 0), prefs.getInt("l${kind.id}", 0), prefs.getInt("d${kind.id}", 0))

    private fun record(kind: GameKind, result: Int) {
        val key = when (result) { 1 -> "w"; -1 -> "l"; else -> "d" } + kind.id
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun gsend(kind: GameKind, op: Int, id: Int, value: Int = 0) =
        send(A.GAME, Out().b(kind.id.toByte()).b(op.toByte()).i(id).i(value).build())

    fun invite(kind: GameKind): Boolean {
        if (engine.currentSession() == null) return false
        val id = (Crypto.random(4).fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xff) }) and 0x7fffffff
        _game.value = GameState(kind, id, iStarted = true, phase = GamePhase.WAITING_ACCEPT, board = GameRules.emptyBoard(kind))
        gsend(kind, 0, id)
        return true
    }

    fun accept() {
        val gs = _game.value ?: return
        if (gs.phase != GamePhase.INVITED_ME) return
        _game.value = gs.copy(phase = GamePhase.PLAYING)
        gsend(gs.kind, 1, gs.id)
    }

    fun quit() {
        val gs = _game.value ?: return
        if (gs.phase != GamePhase.DONE) gsend(gs.kind, 3, gs.id)
        _game.value = null
    }

    /** RPS choice, TTT cell, or DUEL tap (value ignored; time measured here). */
    fun move(value: Int) {
        val gs = _game.value ?: return
        when (gs.kind) {
            GameKind.RPS -> {
                if (gs.phase != GamePhase.PLAYING || gs.mine != null) return
                _game.value = settle(gs.copy(mine = value))
                gsend(gs.kind, 2, gs.id, value)
            }
            GameKind.TTT -> {
                if (gs.phase != GamePhase.PLAYING || !gs.myTurn || value !in 0..8 || gs.board[value] != 0) return
                val b = gs.board.toMutableList().also { it[value] = gs.myMark }
                _game.value = settle(gs.copy(board = b))
                gsend(gs.kind, 2, gs.id, value)
            }
            GameKind.C4 -> {
                if (gs.phase != GamePhase.PLAYING || !gs.myTurn) return
                val cell = GameRules.c4Drop(gs.board, value); if (cell < 0) return
                _game.value = settle(gs.copy(board = gs.board.toMutableList().also { it[cell] = gs.myMark }))
                gsend(gs.kind, 2, gs.id, value)
            }
            GameKind.MEMORY -> {
                if (gs.phase != GamePhase.PLAYING || !gs.myTurn) return
                val next = GameRules.memFlip(gs, value, byStarter = gs.iStarted) ?: return
                _game.value = settle(next)
                gsend(gs.kind, 2, gs.id, value)
            }
            GameKind.DICE -> {
                if (gs.phase != GamePhase.PLAYING || gs.mine != null) return
                val rnd = Crypto.random(2)
                val roll = ((rnd[0].toInt() and 0xff) % 6 + 1) * 10 + ((rnd[1].toInt() and 0xff) % 6 + 1)
                _game.value = settle(gs.copy(mine = roll))
                gsend(gs.kind, 2, gs.id, roll)
            }
            GameKind.DUEL -> {
                if (gs.mine != null) return
                val now = System.currentTimeMillis()
                val ms = if (gs.phase == GamePhase.GO) (now - gs.goAt).toInt().coerceIn(1, 9998) else 9999   // 9999 = too early
                _game.value = settle(gs.copy(mine = ms, phase = if (gs.phase == GamePhase.COUNTDOWN) GamePhase.GO else gs.phase))
                gsend(gs.kind, 2, gs.id, ms)
            }
        }
    }

    private fun onGame(r: In) {
        val kind = GameKind.of(r.b().toInt()) ?: return
        val op = r.b().toInt(); val id = r.i(); val value = r.i()
        val gs = _game.value
        when (op) {
            0 -> {
                _game.value = GameState(kind, id, iStarted = false, phase = GamePhase.INVITED_ME, board = GameRules.emptyBoard(kind))
                buzz(longArrayOf(0, 60, 60, 60))
                if (!g.uiVisibility.appVisible) g.notifier.showInfo("🎮 ${name()} wants to play", kind.title)
                else com.wristradar.ui.components.BannerBus.show(name(), "🎮 wants to play ${kind.title}")
            }
            1 -> if (gs != null && gs.id == id && gs.phase == GamePhase.WAITING_ACCEPT) {
                if (kind == GameKind.DUEL) {
                    val delayMs = 2000 + (Crypto.random(2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) } % 3000)
                    gsend(kind, 4, id, delayMs)
                    startCountdown(gs.copy(phase = GamePhase.COUNTDOWN), delayMs)
                } else _game.value = gs.copy(phase = GamePhase.PLAYING)
            }
            2 -> if (gs != null && gs.id == id) {
                _game.value = when (kind) {
                    GameKind.TTT -> {
                        if (value !in 0..8 || gs.board[value] != 0 || gs.myTurn) gs
                        else settle(gs.copy(board = gs.board.toMutableList().also { it[value] = 3 - gs.myMark }))
                    }
                    GameKind.C4 -> {
                        val cell = GameRules.c4Drop(gs.board, value)
                        if (cell < 0 || gs.myTurn) gs else settle(gs.copy(board = gs.board.toMutableList().also { it[cell] = 3 - gs.myMark }))
                    }
                    GameKind.MEMORY -> if (gs.myTurn) gs else GameRules.memFlip(gs, value, byStarter = !gs.iStarted)?.let { settle(it) } ?: gs
                    GameKind.DICE -> if (GameRules.validDice(value)) settle(gs.copy(theirs = value)) else gs
                    else -> settle(gs.copy(theirs = value))
                }
            }
            3 -> if (gs != null && gs.id == id && gs.phase != GamePhase.DONE) {
                _game.value = null
                com.wristradar.ui.components.BannerBus.show(name(), "left the game")
            }
            4 -> if (gs != null && gs.id == id && kind == GameKind.DUEL) startCountdown(gs.copy(phase = GamePhase.COUNTDOWN), value.coerceIn(1000, 6000))
        }
    }

    private fun startCountdown(gs: GameState, delayMs: Int) {
        val goAt = System.currentTimeMillis() + delayMs
        _game.value = gs.copy(goAt = goAt)
        engine.scope.launch {
            delay(delayMs.toLong())
            val cur = _game.value
            if (cur != null && cur.id == gs.id && cur.phase == GamePhase.COUNTDOWN) {
                _game.value = cur.copy(phase = GamePhase.GO, goAt = System.currentTimeMillis())
                buzz(longArrayOf(0, 120))
            }
        }
    }

    /** Works out a result once both sides have played. */
    private fun settle(gs: GameState): GameState {
        val result: Int? = when (gs.kind) {
            GameKind.RPS -> if (gs.mine == null || gs.theirs == null) null
                else if (gs.mine == gs.theirs) 0 else if ((gs.mine - gs.theirs + 3) % 3 == 1) 1 else -1
            GameKind.DUEL -> if (gs.mine == null || gs.theirs == null) null
                else if (gs.mine == gs.theirs) 0 else if (gs.mine < gs.theirs) 1 else -1
            GameKind.TTT -> {
                val w = tttWinner(gs.board)
                when { w == gs.myMark -> 1; w != 0 -> -1; gs.board.none { it == 0 } -> 0; else -> null }
            }
            GameKind.C4 -> {
                val w = GameRules.c4Winner(gs.board)
                when { w == gs.myMark -> 1; w != 0 -> -1; gs.board.none { it == 0 } -> 0; else -> null }
            }
            GameKind.MEMORY -> if (gs.board.any { it == 0 }) null else {
                val mine = gs.board.count { it == gs.myMark } / 2
                val theirs = gs.board.count { it != gs.myMark } / 2
                mine.compareTo(theirs).coerceIn(-1, 1)
            }
            GameKind.DICE -> if (gs.mine == null || gs.theirs == null) null
                else GameRules.diceSum(gs.mine).compareTo(GameRules.diceSum(gs.theirs)).coerceIn(-1, 1)
        }
        if (result == null) return gs
        record(gs.kind, result)
        buzz(if (result == 1) longArrayOf(0, 60, 50, 60, 50, 200) else longArrayOf(0, 150))
        return gs.copy(phase = GamePhase.DONE, result = result)
    }

    private fun tttWinner(b: List<Int>): Int {
        val lines = listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), listOf(0, 4, 8), listOf(2, 4, 6))
        for (l in lines) if (b[l[0]] != 0 && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]]) return b[l[0]]
        return 0
    }

    companion object { const val MAX_TODOS = 100 }

    // ------------------------------------------------------------------ vibration

    private fun buzz(pattern: LongArray) {
        if (!g.settings.current.vibration) return
        val vm = ctx.getSystemService(VibratorManager::class.java) ?: return
        runCatching { vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
}
