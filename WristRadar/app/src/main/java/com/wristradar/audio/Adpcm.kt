package com.wristradar.audio

import java.nio.ByteBuffer

/**
 * IMA ADPCM (4 bits/sample). Standard algorithm, implemented identically on the PC companion.
 * Block layout: predictor int16 BE | index u8 | packed nibbles (low nibble = earlier sample).
 * Each block carries its own state, so a lost call packet never corrupts the next one.
 */
object Adpcm {
    const val SAMPLE_RATE = 8000
    /** 60 ms of audio per call packet. */
    const val CALL_FRAME = 480

    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
        107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
        27086, 29794, 32767,
    )

    class State(var predictor: Int = 0, var index: Int = 0)

    fun encodeBlock(pcm: ShortArray, count: Int, st: State): ByteArray {
        val n = count and 1.inv()
        val out = ByteArray(3 + n / 2)
        out[0] = (st.predictor shr 8).toByte(); out[1] = st.predictor.toByte(); out[2] = st.index.toByte()
        var i = 0
        while (i < n) {
            val lo = encodeSample(pcm[i].toInt(), st)
            val hi = encodeSample(pcm[i + 1].toInt(), st)
            out[3 + i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    fun decodeBlock(block: ByteArray, offset: Int = 0, length: Int = block.size - offset): ShortArray {
        if (length < 3) return ShortArray(0)
        var pred = ((block[offset].toInt() shl 8) or (block[offset + 1].toInt() and 0xff)).toShort().toInt()
        var idx = (block[offset + 2].toInt() and 0xff).coerceIn(0, 88)
        val out = ShortArray((length - 3) * 2)
        var o = 0
        for (k in offset + 3 until offset + length) {
            val b = block[k].toInt() and 0xff
            for (nib in intArrayOf(b and 0x0f, b shr 4)) {
                val step = STEP[idx]
                var diff = step shr 3
                if (nib and 4 != 0) diff += step
                if (nib and 2 != 0) diff += step shr 1
                if (nib and 1 != 0) diff += step shr 2
                pred = (if (nib and 8 != 0) pred - diff else pred + diff).coerceIn(-32768, 32767)
                idx = (idx + INDEX[nib]).coerceIn(0, 88)
                out[o++] = pred.toShort()
            }
        }
        return out
    }

    private fun encodeSample(sample: Int, st: State): Int {
        var diff = sample - st.predictor
        var nib = 0
        if (diff < 0) { nib = 8; diff = -diff }
        var step = STEP[st.index]
        var vpdiff = step shr 3
        if (diff >= step) { nib = nib or 4; diff -= step; vpdiff += step }
        step = step shr 1
        if (diff >= step) { nib = nib or 2; diff -= step; vpdiff += step }
        step = step shr 1
        if (diff >= step) { nib = nib or 1; vpdiff += step }
        st.predictor = (if (nib and 8 != 0) st.predictor - vpdiff else st.predictor + vpdiff).coerceIn(-32768, 32767)
        st.index = (st.index + INDEX[nib]).coerceIn(0, 88)
        return nib
    }

    // ---- voice message container: "WRV1" | rate int | samples int | one ADPCM block ----
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'R'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

    fun packVoice(pcm: ShortArray, count: Int): ByteArray {
        val block = encodeBlock(pcm, count, State())
        return ByteBuffer.allocate(12 + block.size).put(MAGIC).putInt(SAMPLE_RATE).putInt(count and 1.inv()).put(block).array()
    }

    /** Returns PCM, or null if this is not a valid WristRadar voice message. */
    fun unpackVoice(b: ByteArray): ShortArray? {
        if (b.size < 15 || !b.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val bb = ByteBuffer.wrap(b, 4, 8)
        val rate = bb.int; val samples = bb.int
        if (rate != SAMPLE_RATE || samples < 0 || samples > SAMPLE_RATE * 120 || 3 + samples / 2 != b.size - 12) return null
        return decodeBlock(b, 12, b.size - 12)
    }

    fun durationMs(voice: ByteArray): Int =
        if (voice.size < 12) 0 else (ByteBuffer.wrap(voice, 8, 4).int * 1000L / SAMPLE_RATE).toInt()
}
