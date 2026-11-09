package com.wristradar

import com.google.crypto.tink.subtle.Ed25519Sign
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.HandshakeClient
import com.wristradar.crypto.HandshakeServer
import com.wristradar.crypto.MlKem
import com.wristradar.crypto.Signer
import com.wristradar.data.AppLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Id : Signer {
    private val kp = Ed25519Sign.KeyPair.newKeyPair()
    override val publicKey: ByteArray = kp.publicKey
    override fun sign(data: ByteArray): ByteArray = Ed25519Sign(kp.privateKey).sign(data)
}

class StrongerSecurityTest {
    private fun run(level: MlKem.Level) {
        val a = Id(); val b = Id(); val psk = Crypto.random(32)
        val c = HandshakeClient(a, b.publicKey, psk, level)
        val s = HandshakeServer(b, a.publicKey, psk)
        val hello = c.hello()
        assertEquals(48 + level.publicKeySize, hello.size)
        val (fin, kc) = c.onServer(s.onHello(hello)!!)!!
        val ks = s.onFinish(fin)!!
        assertArrayEquals(kc.sendKey, ks.recvKey)
        assertArrayEquals(kc.recvKey, ks.sendKey)
        assertEquals(level, kc.kem); assertEquals(level, ks.kem)
    }

    @Test fun ml_kem_1024_session_handshake_works() = run(MlKem.Level.ML_KEM_1024)

    /** Older apps keep connecting: the new server still accepts ML-KEM-768 hellos. */
    @Test fun ml_kem_768_still_accepted_for_older_apps() = run(MlKem.Level.ML_KEM_768)

    @Test fun ml_kem_1024_roundtrip() {
        val kp = MlKem.generate(MlKem.Level.ML_KEM_1024)
        assertEquals(1568, kp.publicKey.size)
        val (ct, ss) = MlKem.encapsulate(kp.publicKey)
        assertEquals(1568, ct.size)
        assertArrayEquals(ss, MlKem.decapsulate(kp, ct))
    }

    /** A hello of any other size is refused (no guessing of parameters). */
    @Test fun odd_sized_hello_is_rejected() {
        val a = Id(); val b = Id(); val psk = Crypto.random(32)
        assertNull(HandshakeServer(b, a.publicKey, psk).onHello(Crypto.random(48 + 1000)))
    }

    /** A 1024 response can't be fed to a 768 client (the transcript label differs). */
    @Test fun strength_cannot_be_swapped_mid_handshake() {
        val a = Id(); val b = Id(); val psk = Crypto.random(32)
        val c768 = HandshakeClient(a, b.publicKey, psk, MlKem.Level.ML_KEM_768)
        val c1024 = HandshakeClient(a, b.publicKey, psk, MlKem.Level.ML_KEM_1024)
        val resp1024 = HandshakeServer(b, a.publicKey, psk).onHello(c1024.hello())!!
        assertNull(c768.onServer(resp1024))
        assertNotNull(c1024.onServer(resp1024))
    }

    @Test fun pin_rules() {
        assertTrue(AppLock.valid("1234")); assertTrue(AppLock.valid("123456"))
        assertFalse(AppLock.valid("123")); assertFalse(AppLock.valid("1234567")); assertFalse(AppLock.valid("12a4"))
    }
}
