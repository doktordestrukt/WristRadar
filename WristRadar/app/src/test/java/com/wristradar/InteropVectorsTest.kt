package com.wristradar

import com.google.crypto.tink.subtle.Ed25519Sign
import com.wristradar.ble.AdvertPayload
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.toHex
import com.wristradar.data.Partner
import org.junit.Test

/** Prints known-answer vectors consumed by the Windows companion's self-test (WristRadarPC --selftest). */
class InteropVectorsTest {
    @Test fun printVectors() {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val ed = Ed25519Sign(seed)
        val edPub = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey
        val a = ByteArray(32) { 0x11 }; val b = ByteArray(32) { 0x22 }
        val out = buildString {
            appendLine("ed_pub=" + edPub.toHex())
            appendLine("ed_sig=" + ed.sign("hello".toByteArray()).toHex())
            appendLine("x_pubA=" + Crypto.x25519Public(a).toHex())
            appendLine("x_shared=" + Crypto.x25519(a, Crypto.x25519Public(b)).toHex())
            appendLine("hkdf=" + Crypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, "wr psk v2").toHex())
            appendLine("hkdf_nosalt=" + Crypto.hkdf(ByteArray(32) { 0x33 }, null, "wr rekey").toHex())
            appendLine("gcm=" + Crypto.gcmSeal(ByteArray(32) { 0x33 }, ByteArray(12) { 0x44 }, "msg".toByteArray(), "aad".toByteArray()).toHex())
            val p = Partner(ByteArray(32) { 0x66 }, ByteArray(32) { 0x55 }, 0)
            appendLine("token=" + AdvertPayload.token(p.advertKeyFor(ByteArray(32) { 0x77 }), 123).toHex())
            val sine = ShortArray(32) { (8000 * kotlin.math.sin(it * 0.7)).toInt().toShort() }
            appendLine("adpcm=" + com.wristradar.audio.Adpcm.encodeBlock(sine, 32, com.wristradar.audio.Adpcm.State()).toHex())
        }
        java.io.File("build/interop-vectors.txt").writeText(out)
        println(out)
    }
}
