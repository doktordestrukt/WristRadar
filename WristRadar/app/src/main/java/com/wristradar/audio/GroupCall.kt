package com.wristradar.audio

import android.content.Context
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.VibratorManager
import com.wristradar.AppGraph
import com.wristradar.core.RadarEngine
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.toHex
import com.wristradar.protocol.A
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Group voice calls (MK19). Every participant sends its microphone (ADPCM, 60 ms blocks) to each
 * other participant over their normal encrypted 1:1 session, and mixes everything it receives.
 * Only participants with a direct session hear each other (no relaying of live audio).
 *
 * GCALL = op(1) | callId(16) | data:
 *   0 INVITE  groupId(16)        1 JOIN  reply(1)        2 LEAVE        3 AUDIO  seq(int) | adpcm
 *   4 MUTE    on(1)
 */
class GroupCall(private val ctx: Context, private val g: AppGraph, private val engine: RadarEngine) {

    enum class Phase { INCOMING, ACTIVE, ENDED }

    data class State(
        val phase: Phase,
        val groupId: String,
        val groupName: String,
        val callId: String,
        val since: Long = 0L,
        /** contact id -> name, everyone in the call except me. */
        val people: Map<String, String> = emptyMap(),
        val mutedPeople: Set<String> = emptySet(),
        val muted: Boolean = false,
        val invitedBy: String? = null,
        val endReason: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    @Volatile private var running = false
    @Volatile private var muted = false
    private var callIdBytes: ByteArray? = null
    private val queues = ConcurrentHashMap<String, Channel<ShortArray>>()
    private val am = ctx.getSystemService(AudioManager::class.java)

    // ---------------------------------------------------------------- user actions

    /** Starts a call with everyone in a group who is connected right now. */
    fun start(groupId: String): Boolean {
        if (_state.value?.phase == Phase.ACTIVE || engine.calls.state.value.live) return false
        val grp = engine.groups.group(groupId) ?: return false
        val id = Crypto.random(16)
        callIdBytes = id
        _state.value = State(Phase.ACTIVE, grp.id, grp.name, id.toHex(), since = System.currentTimeMillis())
        engine.scope.launch {
            for (m in grp.members) {
                if (m.id == myId) continue
                engine.sessionFor(m.id)?.send(A.GCALL, Out().b(OP_INVITE.toByte()).raw(id).raw(grp.id.hexBytes()).build())
            }
        }
        startAudio()
        engine.scope.launch {
            kotlinx.coroutines.delay(45_000)
            val now = _state.value
            if (now != null && now.callId == id.toHex() && now.phase == Phase.ACTIVE && now.people.isEmpty()) end("No one answered", tell = true)
        }
        return true
    }

    fun accept() {
        val st = _state.value ?: return
        if (st.phase != Phase.INCOMING) return
        stopRinging()
        _state.value = st.copy(phase = Phase.ACTIVE, since = System.currentTimeMillis())
        broadcast(Out().b(OP_JOIN.toByte()).raw(callIdBytes!!).b(0).build(), st.groupId)
        startAudio()
    }

    fun decline() { stopRinging(); end("Declined", tell = false) }

    fun hangUp() = end("Call ended", tell = true)

    fun toggleMute() {
        val st = _state.value ?: return
        muted = !muted
        _state.value = st.copy(muted = muted)
        callIdBytes?.let { broadcast(Out().b(OP_MUTE.toByte()).raw(it).b((if (muted) 1 else 0).toByte()).build(), st.groupId) }
    }

    fun dismiss() { if (_state.value?.phase == Phase.ENDED) _state.value = null }

    // ---------------------------------------------------------------- signalling

    fun onSignal(type: Byte, p: ByteArray, from: String) {
        if (type == A.GCALL) onPacket(p, from)
    }

    fun onPacket(p: ByteArray, from: String) {
        val r = In(p)
        val op = r.b().toInt()
        val cid = r.raw(16)
        val st = _state.value
        val same = callIdBytes?.contentEquals(cid) == true
        when (op) {
            OP_INVITE -> {
                val gid = r.raw(16).toHex()
                val grp = engine.groups.group(gid) ?: return
                if (!grp.has(from)) return
                if (st != null && st.phase != Phase.ENDED) return            // busy: they'll see we never joined
                if (engine.calls.state.value.live) return
                callIdBytes = cid
                _state.value = State(Phase.INCOMING, gid, grp.name, cid.toHex(),
                    people = mapOf(from to (grp.nameOf(from) ?: "Someone")), invitedBy = grp.nameOf(from))
                startRinging()
                g.notifier.showIncomingCall("${grp.nameOf(from) ?: "Someone"} · ${grp.name}")
            }
            OP_JOIN -> if (same && st != null) {
                val reply = r.b().toInt() == 1
                val name = engine.groups.group(st.groupId)?.nameOf(from) ?: "Someone"
                _state.update { it?.copy(people = it.people + (from to name)) }
                if (!reply && st.phase == Phase.ACTIVE) engine.scope.launch {
                    engine.sessionFor(from)?.send(A.GCALL, Out().b(OP_JOIN.toByte()).raw(cid).b(1).build())
                }
            }
            OP_LEAVE -> if (same) {
                queues.remove(from)
                _state.update { it?.copy(people = it.people - from, mutedPeople = it.mutedPeople - from) }
                val now = _state.value
                if (now?.phase == Phase.INCOMING) { stopRinging(); end("Missed group call", tell = false) }
                else if (now?.phase == Phase.ACTIVE && now.people.isEmpty()) end("Everyone left", tell = false)
            }
            OP_AUDIO -> if (same && running) {
                r.i()
                val block = r.raw(p.size - 1 - 16 - 4)
                if (_state.value?.people?.containsKey(from) != true) {
                    val name = engine.groups.group(st?.groupId ?: "")?.nameOf(from) ?: return
                    _state.update { it?.copy(people = it.people + (from to name)) }
                }
                queues.getOrPut(from) { Channel(5, BufferOverflow.DROP_OLDEST) }.trySend(Adpcm.decodeBlock(block))
            }
            OP_MUTE -> if (same) {
                val on = r.b().toInt() == 1
                _state.update { it?.copy(mutedPeople = if (on) it.mutedPeople + from else it.mutedPeople - from) }
            }
        }
    }

    /** A contact's link dropped: they're out of the call. */
    fun onPeerLinkLost(id: String) {
        queues.remove(id)
        val had = _state.value?.people?.containsKey(id) == true
        _state.update { it?.copy(people = it.people - id) }
        val now = _state.value
        if (had && now?.phase == Phase.ACTIVE && now.people.isEmpty()) end("Call ended", tell = false)
        if (now?.phase == Phase.INCOMING && had) { stopRinging(); end("Missed group call", tell = false) }
    }

    // ---------------------------------------------------------------- internals

    private val myId: String get() = g.identity.publicKey.toHex()

    private fun broadcast(pkt: ByteArray, groupId: String) {
        val grp = engine.groups.group(groupId) ?: return
        engine.scope.launch { for (m in grp.members) if (m.id != myId) engine.sessionFor(m.id)?.send(A.GCALL, pkt) }
    }

    private fun end(reason: String, tell: Boolean) {
        val st = _state.value ?: return
        if (tell) callIdBytes?.let { broadcast(Out().b(OP_LEAVE.toByte()).raw(it).build(), st.groupId) }
        stopAudio()
        g.notifier.cancelCall()
        callIdBytes = null
        _state.value = st.copy(phase = Phase.ENDED, endReason = reason)
    }

    private fun startAudio() {
        if (running) return
        running = true
        muted = false
        runCatching { am?.mode = AudioManager.MODE_IN_COMMUNICATION }
        runCatching { am?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) am?.availableCommunicationDevices
                ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
        }
        val cid = callIdBytes ?: return
        // microphone -> one ADPCM block -> every participant
        Thread {
            val rec = if (hasMicPermission(ctx)) openMic() else null
            val fx = rec?.let { enableVoiceEffects(it) }.orEmpty()
            val frame = ShortArray(Adpcm.CALL_FRAME)
            val enc = Adpcm.State()
            rec?.startRecording()
            var seq = 0
            while (running) {
                if (rec == null) { Thread.sleep(60); continue }
                var got = 0
                while (got < frame.size && running) { val n = rec.read(frame, got, frame.size - got); if (n <= 0) break; got += n }
                if (!running || got < frame.size) continue
                if (muted) frame.fill(0) else { Gain.gate(frame, frame.size); Gain.apply(frame, frame.size, 1.6f) }
                val pkt = Out().b(OP_AUDIO.toByte()).raw(cid).i(seq++).raw(Adpcm.encodeBlock(frame, frame.size, enc)).build()
                val people = _state.value?.people?.keys.orEmpty()
                engine.scope.launch { for (id in people) engine.sessionFor(id)?.send(A.GCALL, pkt) }
            }
            fx.forEach { runCatching { it.release() } }
            rec?.let { runCatching { it.stop() }; it.release() }
        }.apply { name = "wr-gcall-mic"; start() }
        // everyone's audio -> mixed -> speaker (track.write paces the loop in real time)
        Thread {
            val track = openSpeaker(streaming = true)
            track.play()
            val mix = IntArray(Adpcm.CALL_FRAME)
            val out = ShortArray(Adpcm.CALL_FRAME)
            while (running) {
                mix.fill(0)
                var any = false
                for (q in queues.values) {
                    val pcm = q.tryReceive().getOrNull() ?: continue
                    any = true
                    for (i in 0 until minOf(pcm.size, mix.size)) mix[i] += pcm[i].toInt()
                }
                if (!any) { Thread.sleep(15); continue }
                for (i in out.indices) out[i] = mix[i].coerceIn(-32768, 32767).toShort()
                Gain.apply(out, out.size, g.settings.current.callBoost)
                track.write(out, 0, out.size)
            }
            runCatching { track.stop() }; track.release()
        }.apply { name = "wr-gcall-spk"; start() }
    }

    private fun stopAudio() {
        if (!running) return
        running = false
        queues.clear()
        runCatching { if (android.os.Build.VERSION.SDK_INT >= 31) am?.clearCommunicationDevice() }
        runCatching { am?.mode = AudioManager.MODE_NORMAL }
    }

    private fun startRinging() {
        if (!g.settings.current.vibration) return
        runCatching {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 400, 400, 900), 0))
        }
    }

    private fun stopRinging() {
        runCatching { ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel() }
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        const val OP_INVITE = 0
        const val OP_JOIN = 1
        const val OP_LEAVE = 2
        const val OP_AUDIO = 3
        const val OP_MUTE = 4
    }
}
