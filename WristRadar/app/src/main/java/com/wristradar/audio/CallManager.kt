package com.wristradar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.wristradar.AppGraph
import com.wristradar.core.RadarEngine
import com.wristradar.crypto.Crypto
import com.wristradar.protocol.A
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class CallState {
    data object Idle : CallState()
    data object Outgoing : CallState()
    data object Incoming : CallState()
    data class Active(val since: Long, val muted: Boolean = false) : CallState()
    data object NoAnswer : CallState()
    data class Ended(val reason: String, val durationMs: Long = 0) : CallState()

    val live: Boolean get() = this is Outgoing || this is Incoming || this is Active
}

/**
 * Encrypted voice calls over the existing session. Signalling: CALL_INVITE / ACCEPT / DECLINE / END
 * (16-byte call id). Audio: CALL_AUDIO = call id | seq int | ADPCM block (60 ms, 8 kHz).
 * Real-time audio is never queued for long: at most 3 packets wait, older ones are dropped.
 */
class CallManager(private val ctx: Context, private val g: AppGraph, private val engine: RadarEngine) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private var callId: ByteArray? = null
    private var timeoutJob: Job? = null
    @Volatile private var audioRunning = false
    @Volatile private var muted = false
    private var micThread: Thread? = null
    private var spkThread: Thread? = null
    private var sendJob: Job? = null
    private val outQueue = Channel<ByteArray>(3, BufferOverflow.DROP_OLDEST)
    private var inQueue = Channel<ShortArray>(6, BufferOverflow.DROP_OLDEST)
    private var ringtone: Ringtone? = null
    private val am = ctx.getSystemService(AudioManager::class.java)

    // ------------------------------------------------------------ user actions
    fun startCall(): Boolean {
        if (_state.value.live) return false
        val s = engine.currentSession() ?: run { _state.value = CallState.Ended("Not connected"); return false }
        val id = Crypto.random(16)
        callId = id
        _state.value = CallState.Outgoing
        engine.scope.launch { s.send(A.CALL_INVITE, id) }
        armTimeout(30_000) {
            send(A.CALL_END, Out().raw(id).str("noanswer").build())
            callId = null
            _state.value = CallState.NoAnswer
        }
        return true
    }

    fun accept() {
        val id = callId ?: return
        if (_state.value != CallState.Incoming) return
        stopRinging()
        timeoutJob?.cancel()
        send(A.CALL_ACCEPT, id)
        goActive()
    }

    fun decline() {
        val id = callId ?: return
        stopRinging(); timeoutJob?.cancel()
        send(A.CALL_DECLINE, Out().raw(id).str("declined").build())
        callId = null
        g.notifier.cancelCall()
        _state.value = CallState.Ended("Declined")
    }

    fun hangUp() {
        val id = callId
        val st = _state.value
        stopRinging(); timeoutJob?.cancel()
        if (id != null) send(A.CALL_END, Out().raw(id).str("hangup").build())
        finish(if (st is CallState.Active) "Call ended" else "Cancelled")
    }

    fun toggleMute() {
        val st = _state.value as? CallState.Active ?: return
        muted = !st.muted
        _state.value = st.copy(muted = muted)
    }

    fun dismissResult() { if (!_state.value.live) _state.value = CallState.Idle }

    // ------------------------------------------------------------ signalling from partner
    fun onSignal(type: Byte, p: ByteArray) {
        val r = In(p)
        val id = r.raw(16)
        when (type) {
            A.CALL_INVITE -> {
                if (_state.value.live) { send(A.CALL_DECLINE, Out().raw(id).str("busy").build()); return }
                callId = id
                _state.value = CallState.Incoming
                startRinging()
                g.notifier.showIncomingCall(g.profile.partnerName.value ?: "Partner")
                armTimeout(32_000) {
                    stopRinging(); callId = null
                    g.notifier.cancelCall()
                    g.notifier.showInfo("Missed call", "You missed a call from ${g.profile.partnerName.value ?: "your partner"}")
                    _state.value = CallState.Ended("Missed call")
                }
            }
            A.CALL_ACCEPT -> if (callId.contentEqualsSafe(id) && _state.value == CallState.Outgoing) {
                timeoutJob?.cancel(); goActive()
            }
            A.CALL_DECLINE -> if (callId.contentEqualsSafe(id)) {
                val why = runCatching { r.str(40) }.getOrDefault("")
                timeoutJob?.cancel(); callId = null
                stopAudio()
                _state.value = if (why == "busy") CallState.Ended("Busy") else CallState.NoAnswer
            }
            A.CALL_END -> if (callId.contentEqualsSafe(id)) {
                val why = runCatching { r.str(40) }.getOrDefault("")
                stopRinging(); timeoutJob?.cancel()
                g.notifier.cancelCall()
                if (why == "noanswer" && _state.value == CallState.Incoming) {
                    g.notifier.showInfo("Missed call", "You missed a call from ${g.profile.partnerName.value ?: "your partner"}")
                    callId = null; _state.value = CallState.Ended("Missed call")
                } else finish("Call ended")
            }
            A.CALL_AUDIO -> if (callId.contentEqualsSafe(id) && audioRunning) {
                r.i() // sequence (ordering is guaranteed by the link; kept for diagnostics)
                val block = r.raw(p.size - 20)
                inQueue.trySend(Adpcm.decodeBlock(block))
            }
        }
    }

    fun onLinkLost() {
        if (!_state.value.live) return
        stopRinging(); timeoutJob?.cancel()
        g.notifier.cancelCall()
        finish("Connection lost")
    }

    // ------------------------------------------------------------ internals
    private fun ByteArray?.contentEqualsSafe(o: ByteArray) = this != null && this.contentEquals(o)

    private fun send(type: Byte, payload: ByteArray) {
        val s = engine.currentSession() ?: return
        engine.scope.launch { s.send(type, payload) }
    }

    private fun armTimeout(ms: Long, block: () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = engine.scope.launch { delay(ms); block() }
    }

    private fun finish(reason: String) {
        val st = _state.value
        val dur = if (st is CallState.Active) System.currentTimeMillis() - st.since else 0
        callId = null
        stopAudio()
        g.notifier.cancelCall()
        _state.value = CallState.Ended(reason, dur)
    }

    private fun goActive() {
        g.notifier.cancelCall()
        muted = false
        _state.value = CallState.Active(System.currentTimeMillis())
        startAudio()
    }

    private fun startAudio() {
        if (audioRunning) return
        if (!hasMicPermission(ctx)) Log.w("WristRadar", "call without microphone permission: receive only")
        audioRunning = true
        inQueue = Channel(6, BufferOverflow.DROP_OLDEST)
        runCatching { am?.mode = AudioManager.MODE_IN_COMMUNICATION }
        loudSpeakerOn()
        val id = callId ?: return
        // microphone -> ADPCM -> queue
        micThread = Thread {
            val rec = if (hasMicPermission(ctx)) openMic() else null
            val fx = rec?.let { enableVoiceEffects(it) }.orEmpty()
            val frame = ShortArray(Adpcm.CALL_FRAME)
            val st = Adpcm.State()
            rec?.startRecording()
            var seq = 0
            while (audioRunning) {
                if (rec == null) { Thread.sleep(60); continue }
                var got = 0
                while (got < frame.size && audioRunning) { val n = rec.read(frame, got, frame.size - got); if (n <= 0) break; got += n }
                if (!audioRunning || got < frame.size) continue
                if (muted) frame.fill(0) else { Gain.gate(frame, frame.size); Gain.apply(frame, frame.size, MIC_GAIN) }
                outQueue.trySend(Out().raw(id).i(seq++).raw(Adpcm.encodeBlock(frame, frame.size, st)).build())
            }
            fx.forEach { runCatching { it.release() } }
            rec?.let { runCatching { it.stop() }; it.release() }
        }.apply { name = "wr-call-mic"; start() }
        // queue -> encrypted session
        sendJob = engine.scope.launch {
            for (pkt in outQueue) {
                if (!audioRunning) break
                engine.currentSession()?.send(A.CALL_AUDIO, pkt)
            }
        }
        // received audio -> speaker
        val q = inQueue
        spkThread = Thread {
            val track = openSpeaker(streaming = true)
            track.play()
            while (audioRunning) {
                val pcm = q.tryReceive().getOrNull()
                if (pcm == null) { Thread.sleep(10); continue }
                Gain.apply(pcm, pcm.size, g.settings.current.callBoost)
                track.write(pcm, 0, pcm.size)
            }
            runCatching { track.stop() }; track.release()
        }.apply { name = "wr-call-spk"; start() }
    }

    private fun stopAudio() {
        if (!audioRunning) return
        audioRunning = false
        sendJob?.cancel()
        while (outQueue.tryReceive().isSuccess) { /* drain */ }
        loudSpeakerOff()
        runCatching { am?.mode = AudioManager.MODE_NORMAL }
    }

    /** Volume boost from the call screen (1x..3x), applied live to your friend's voice. */
    fun setBoost(v: Float) = g.settings.update { it.copy(callBoost = v.coerceIn(1f, 3f)) }

    private var savedVolume = -1

    /** Routes the call to the watch loudspeaker and turns call volume all the way up. */
    private fun loudSpeakerOn() {
        val a = am ?: return
        runCatching {
            savedVolume = a.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            a.setStreamVolume(AudioManager.STREAM_VOICE_CALL, a.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                a.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { a.setCommunicationDevice(it) }
            } else @Suppress("DEPRECATION") { a.isSpeakerphoneOn = true }
        }
    }

    private fun loudSpeakerOff() {
        val a = am ?: return
        runCatching { if (android.os.Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice() }
        runCatching { if (savedVolume >= 0) a.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0) }
        savedVolume = -1
    }

    companion object { private const val MIC_GAIN = 1.6f }

    private fun startRinging() {
        runCatching {
            ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                isLooping = true
                play()
            }
        }
        if (g.settings.current.vibration) {
            runCatching {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 700), 0))
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel() }
    }
}
