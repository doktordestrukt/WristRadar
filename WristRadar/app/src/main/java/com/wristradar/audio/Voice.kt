package com.wristradar.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

fun hasMicPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
fun openMic(): AudioRecord? = runCatching {
    val min = AudioRecord.getMinBufferSize(Adpcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    // VOICE_COMMUNICATION enables the platform echo canceller / noise suppressor where available.
    AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, Adpcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Adpcm.CALL_FRAME * 4),
    ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
}.getOrNull()

fun openSpeaker(streaming: Boolean, bytes: Int = 0): AudioTrack {
    val min = AudioTrack.getMinBufferSize(Adpcm.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    return AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (streaming) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(Adpcm.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        )
        .setTransferMode(if (streaming) AudioTrack.MODE_STREAM else AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(if (streaming) maxOf(min, Adpcm.CALL_FRAME * 2 * 4) else maxOf(bytes, min))
        .build()
}

/** Records a voice message (max [MAX_MS]) for voicemail / voice notes. */
class VoiceRecorder {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _elapsed = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsed.asStateFlow()
    @Volatile private var running = false
    private var thread: Thread? = null
    private val buffer = ShortArray(Adpcm.SAMPLE_RATE * MAX_MS / 1000)
    @Volatile private var count = 0

    fun start(onLimit: () -> Unit): Boolean {
        val rec = openMic() ?: return false
        running = true; count = 0
        thread = Thread {
            val chunk = ShortArray(Adpcm.CALL_FRAME)
            rec.startRecording()
            while (running && count < buffer.size) {
                val n = rec.read(chunk, 0, minOf(chunk.size, buffer.size - count))
                if (n <= 0) continue
                System.arraycopy(chunk, 0, buffer, count, n)
                count += n
                _level.value = (chunk.take(n).maxOf { abs(it.toInt()) } / 32768f).coerceIn(0f, 1f)
                _elapsed.value = count * 1000L / Adpcm.SAMPLE_RATE
            }
            runCatching { rec.stop() }; rec.release()
            if (count >= buffer.size) onLimit()
        }.apply { name = "wr-voice-rec"; start() }
        return true
    }

    /** Stops and returns the packed voice message, or null if too short. */
    fun stop(): ByteArray? {
        running = false
        thread?.join(1500)
        return if (count < Adpcm.SAMPLE_RATE / 2) null else Adpcm.packVoice(buffer, count)
    }

    fun cancel() { running = false; thread?.join(1500); count = 0 }

    companion object { const val MAX_MS = 30_000 }
}

/** Plays stored voice messages one at a time. */
object VoicePlayer {
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    private var track: AudioTrack? = null
    private var ticker: Thread? = null

    fun toggle(id: String, voice: ByteArray?) {
        if (_playing.value == id) { stop(); return }
        stop()
        val pcm = voice?.let { Adpcm.unpackVoice(it) } ?: return
        if (pcm.isEmpty()) return
        val t = openSpeaker(streaming = false, bytes = pcm.size * 2)
        t.write(pcm, 0, pcm.size)
        t.notificationMarkerPosition = pcm.size
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) = stop()
            override fun onPeriodicNotification(track: AudioTrack) {}
        })
        track = t; _playing.value = id; _progress.value = 0f
        t.play()
        ticker = Thread {
            while (_playing.value == id) {
                val pos = runCatching { t.playbackHeadPosition }.getOrDefault(0)
                _progress.value = (pos.toFloat() / pcm.size).coerceIn(0f, 1f)
                Thread.sleep(100)
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        _playing.value = null; _progress.value = 0f
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
