package com.wristradar

import com.wristradar.crypto.Crypto
import com.wristradar.crypto.SafetyCode
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MegaUpdateTest {

    @Test fun safety_code_is_the_same_on_both_watches() {
        val a = Crypto.random(32); val b = Crypto.random(32)
        assertEquals(SafetyCode.compute(a, b), SafetyCode.compute(b, a))
        assertEquals(SafetyCode.LENGTH, SafetyCode.compute(a, b).size)
    }

    @Test fun safety_code_changes_if_a_key_is_swapped() {
        val a = Crypto.random(32); val b = Crypto.random(32); val evil = Crypto.random(32)
        assertNotEquals(SafetyCode.compute(a, b), SafetyCode.compute(a, evil))
    }

    @Test fun safety_emojis_are_64_distinct() {
        assertEquals(64, SafetyCode.EMOJI.size)
        assertEquals(64, SafetyCode.EMOJI.toSet().size)
    }

    /** Older apps read the label with str(20); a longer name would break their VERSION parsing. */
    @Test fun release_label_fits_what_older_apps_can_read() {
        assertTrue("label too long: ${BuildConfig.RELEASE_LABEL}", BuildConfig.RELEASE_LABEL.toByteArray().size <= 20)
        assertTrue(BuildConfig.RELEASE_LABEL.isNotBlank())
    }

    /** VERSION payload exactly as the Updater sends and parses it. */
    @Test fun version_message_roundtrips() {
        val p = Out().i(BuildConfig.VERSION_CODE).str(BuildConfig.RELEASE_LABEL).b(1).build()
        val r = In(p)
        assertEquals(BuildConfig.VERSION_CODE, r.i())
        assertEquals(BuildConfig.RELEASE_LABEL, r.str(20))
        assertTrue(r.hasMore()); assertEquals(1.toByte(), r.b())
    }

    /** A VERSION from an old app without the platform byte still parses. */
    @Test fun version_message_without_platform_byte_parses() {
        val r = In(Out().i(8).str("V8").build())
        assertEquals(8, r.i()); assertEquals("V8", r.str(20)); assertTrue(!r.hasMore())
    }
}
