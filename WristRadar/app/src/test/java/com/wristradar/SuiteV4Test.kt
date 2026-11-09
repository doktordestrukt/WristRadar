package com.wristradar

import com.google.crypto.tink.subtle.Ed25519Sign
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.HandshakeClient
import com.wristradar.crypto.HandshakeServer
import com.wristradar.crypto.MlDsa
import com.wristradar.crypto.MlKem
import com.wristradar.crypto.PqIdentity
import com.wristradar.crypto.SecureChannel
import com.wristradar.crypto.Signer
import com.wristradar.crypto.Suite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Ed : Signer {
    private val kp = Ed25519Sign.KeyPair.newKeyPair()
    override val publicKey: ByteArray = kp.publicKey
    override fun sign(data: ByteArray): ByteArray = Ed25519Sign(kp.privateKey).sign(data)
}

class SuiteV4Test {
    private val a = Ed(); private val b = Ed()
    private val pa = MlDsa.generate(); private val pb = MlDsa.generate()
    private val psk = Crypto.random(32)

    @Test fun ml_dsa_87_sign_verify_and_restore() {
        assertEquals(MlDsa.PUBLIC_KEY_SIZE, pa.publicKey.size)
        val sig = pa.sign("hello".toByteArray())
        assertEquals(MlDsa.SIGNATURE_SIZE, sig.size)
        assertTrue(MlDsa.verify(pa.publicKey, sig, "hello".toByteArray()))
        assertFalse(MlDsa.verify(pa.publicKey, sig, "hellO".toByteArray()))
        assertFalse(MlDsa.verify(pb.publicKey, sig, "hello".toByteArray()))
        val restored = MlDsa.restore(pa.publicKey, pa.privateEncoded)
        assertTrue(MlDsa.verify(pa.publicKey, restored.sign("x".toByteArray()), "x".toByteArray()))
    }

    @Test fun suite_v4_handshake_works_and_channels_talk() {
        val c = HandshakeClient(a, b.publicKey, psk, MlKem.Level.ML_KEM_1024, PqIdentity(pa, pb.publicKey))
        val s = HandshakeServer(b, a.publicKey, psk, PqIdentity(pb, pa.publicKey))
        assertEquals(Suite.V4, c.suite)
        val (fin, kc) = c.onServer(s.onHello(c.hello())!!)!!
        val ks = s.onFinish(fin)!!
        assertEquals(Suite.V4, kc.suite); assertEquals(Suite.V4, ks.suite)
        assertArrayEquals(kc.sendKey, ks.recvKey)
        // records keep flowing across several key ratchets (HKDF-SHA-384)
        val cc = SecureChannel(kc, isClient = true); val sc = SecureChannel(ks, isClient = false)
        repeat(SecureChannel.REKEY_RECORDS * 3 + 5) { i ->
            val msg = "m$i".toByteArray()
            assertArrayEquals(msg, sc.open(cc.seal(msg)))
        }
    }

    @Test fun server_without_pq_keys_refuses_v4_so_client_can_fall_back() {
        val c = HandshakeClient(a, b.publicKey, psk, MlKem.Level.ML_KEM_1024, PqIdentity(pa, pb.publicKey))
        assertNull(HandshakeServer(b, a.publicKey, psk).onHello(c.hello()))
    }

    @Test fun wrong_pq_key_is_caught_and_flagged_as_mismatch_not_intruder() {
        val other = MlDsa.generate()
        val c = HandshakeClient(a, b.publicKey, psk, MlKem.Level.ML_KEM_1024, PqIdentity(pa, other.publicKey))   // expects the wrong key
        val s = HandshakeServer(b, a.publicKey, psk, PqIdentity(pb, pa.publicKey))
        assertNull(c.onServer(s.onHello(c.hello())!!))
        assertTrue(c.failedOnlyPq)
    }

    @Test fun stranger_without_psk_is_not_flagged_as_pq_mismatch() {
        val c = HandshakeClient(a, b.publicKey, Crypto.random(32), MlKem.Level.ML_KEM_1024, PqIdentity(pa, pb.publicKey))
        val s = HandshakeServer(b, a.publicKey, psk, PqIdentity(pb, pa.publicKey))
        assertNull(c.onServer(s.onHello(c.hello())!!))
        assertFalse(c.failedOnlyPq)
    }

    @Test fun older_suites_still_accepted_by_the_new_server() {
        for (lv in listOf(MlKem.Level.ML_KEM_768, MlKem.Level.ML_KEM_1024)) {
            val c = HandshakeClient(a, b.publicKey, psk, lv)
            val s = HandshakeServer(b, a.publicKey, psk, PqIdentity(pb, pa.publicKey))
            val (fin, kc) = c.onServer(s.onHello(c.hello())!!)!!
            assertNotNull(s.onFinish(fin)); assertFalse(kc.suite.wide)
        }
    }

    @Test fun sha384_and_hkdf384_known_answers() {
        // FIPS 180-4 "abc"
        assertEquals("cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7",
            Crypto.sha384("abc".toByteArray()).joinToString("") { "%02x".format(it) })
        assertEquals(32, Crypto.hkdf384(Crypto.random(32), null, "x").size)
        assertFalse(Crypto.hkdf384(ByteArray(32), null, "x").contentEquals(Crypto.hkdf(ByteArray(32), null, "x")))
    }
}
