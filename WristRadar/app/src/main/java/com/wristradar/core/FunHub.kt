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
    RPS(1, "Rock Paper Scissors"), TTT(2, "Tic-Tac-Toe"), DUEL(3, "Reaction Duel");
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
) {
    val myMark get() = if (iStarted) 1 else 2
    val myTurn: Boolean get() {
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
        _game.value = GameState(kind, id, iStarted = true, phase = GamePhase.WAITING_ACCEPT)
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
                _game.value = GameState(kind, id, iStarted = false, phase = GamePhase.INVITED_ME)
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
