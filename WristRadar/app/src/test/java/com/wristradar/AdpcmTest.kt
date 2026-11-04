package com.wristradar

import com.wristradar.audio.Adpcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AdpcmTest {
    private fun tone(n: Int) = ShortArray(n) { (9000 * sin(it * 2 * Math.PI * 440 / Adpcm.SAMPLE_RATE)).toInt().toShort() }

    @Test fun roundtrip_tracks_signal() {
        val pcm = tone(Adpcm.CALL_FRAME)
        val dec = Adpcm.decodeBlock(Adpcm.encodeBlock(pcm, pcm.size, Adpcm.State()))
        assertEquals(pcm.size, dec.size)
        val err = (100 until pcm.size).map { abs(pcm[it] - dec[it]) }.average()
        assertTrue("mean error $err", err < 900)
    }

    @Test fun voice_container_validates() {
        val v = Adpcm.packVoice(tone(8000), 8000)
        assertNotNull(Adpcm.unpackVoice(v))
        assertEquals(1000, Adpcm.durationMs(v))
        assertNull(Adpcm.unpackVoice(v.copyOf(v.size - 5)))
        assertNull(Adpcm.unpackVoice(ByteArray(40)))
    }
}
