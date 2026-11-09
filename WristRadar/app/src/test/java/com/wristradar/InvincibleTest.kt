package com.wristradar

import com.google.crypto.tink.subtle.Ed25519Sign
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.HandshakeClient
import com.wristradar.crypto.HandshakeServer
import com.wristradar.crypto.MlDsa
import com.wristradar.crypto.MlKem
import com.wristradar.crypto.PairingCrypto
import com.wristradar.crypto.PqIdentity
import com.wristradar.crypto.RecordError
import com.wristradar.crypto.SecureChannel
import com.wristradar.crypto.SessionKeys
import com.wristradar.crypto.Signer
import com.wristradar.crypto.Suite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Key : Signer {
    private val kp = Ed25519Sign.KeyPair.newKeyPair()
    override val publicKey: ByteArray = kp.publicKey
    override fun sign(data: ByteArray): ByteArray = Ed25519Sign(kp.privateKey).sign(data)
}

class InvincibleTest {
    private val a = Key(); private val b = Key()
    private val pa = MlDsa.generate(); private val pb = MlDsa.generate()
    private val psk = Crypto.random(32)

    private fun v5(): Pair<SecureChannel, SecureChannel> {
        val c = HandshakeClient(a, b.publicKey, psk, MlKem.Level.ML_KEM_1024, PqIdentity(pa, pb.publicKey), perMessage = true)
        val s = HandshakeServer(b, a.publicKey, psk, PqIdentity(pb, pa.publicKey))
        assertEquals(Suite.V5, c.suite)
        val (fin, kc) = c.onServer(s.onHello(c.hello())!!)!!
        val ks = s.onFinish(fin)!!
        assertEquals(Suite.V5, ks.suite)
        return SecureChannel(kc, true) to SecureChannel(ks, false)
    }

    // ------------------------------------------------------------ per-message keys (V5)

    @Test fun v5_every_message_decrypts_both_ways() {
        val (c, s) = v5()
        repeat(500) { i ->
            assertArrayEquals("c$i".toByteArray(), s.open(c.seal("c$i".toByteArray())))
            assertArrayEquals("s$i".toByteArray(), c.open(s.seal("s$i".toByteArray())))
        }
    }

    @Test fun v5_lost_sends_are_skipped_over() {
        val (c, s) = v5()
        c.seal("lost 1".toByteArray()); c.seal("lost 2".toByteArray())
        assertArrayEquals("third".toByteArray(), s.open(c.seal("third".toByteArray())))
    }

    @Test fun v5_replay_is_refused() {
        val (c, s) = v5()
        val r = c.seal("once".toByteArray())
        assertNotNull(s.open(r))
        assertNull(s.open(r)); assertEquals(RecordError.REPLAY, s.lastError)
    }

    @Test fun v5_forged_far_ahead_record_does_not_move_the_chain() {
        val (c, s) = v5()
        val forged = java.nio.ByteBuffer.allocate(9 + 32).put(0).putLong(1500).put(Crypto.random(32)).array()
        assertNull(s.open(forged))
        assertArrayEquals("still fine".toByteArray(), s.open(c.seal("still fine".toByteArray())))
    }

    @Test fun v5_each_message_uses_a_different_key() {
        // same plaintext twice -> unrelated ciphertexts even apart from the counter
        val (c, _) = v5()
        val x = c.seal("same".toByteArray()); val y = c.seal("same".toByteArray())
        assertFalse(x.copyOfRange(9, x.size).contentEquals(y.copyOfRange(9, y.size)))
    }

    @Test fun older_suites_keep_their_record_format() {
        val k1 = Crypto.random(32); val k2 = Crypto.random(32)
        val c = SecureChannel(SessionKeys(k1, k2), true); val s = SecureChannel(SessionKeys(k2, k1), false)
        repeat(SecureChannel.REKEY_RECORDS + 3) { assertNotNull(s.open(c.seal(byteArrayOf(it.toByte())))) }
    }

    // ------------------------------------------------------------ pairing V3

    private fun pair(initPq: MlDsa.KeyPair?, respPq: MlDsa.KeyPair?, offered: Boolean): Pair<PairingCrypto, PairingCrypto>? {
        val i = PairingCrypto(true, a, initPq)
        val r = PairingCrypto(false, b, respPq, responderV3 = offered)
        r.onPeerCommit(i.myCommit)
        if (!i.onResponse(r.ephPub, r.myId, r.nonce, r.kemPublicKey, if (r.v3) r.myPqPublic else null)) return null
        if (!r.onReveal(i.ephPub, i.myId, i.nonce, i.kemCiphertext!!, if (i.v3) i.myPqPublic else null)) return null
        assertEquals(i.sasCode(), r.sasCode())
        assertTrue(r.verifyPeerConfirmation(i.myConfirmation()))
        assertTrue(i.verifyPeerConfirmation(r.myConfirmation()))
        assertArrayEquals(i.derivePsk(), r.derivePsk())
        return i to r
    }

    @Test fun pairing_v3_between_two_new_watches() {
        val (i, r) = pair(pa, pb, offered = true)!!
        assertTrue(i.v3); assertTrue(r.v3)
        assertArrayEquals(pb.publicKey, i.peerPq); assertArrayEquals(pa.publicKey, r.peerPq)
        assertEquals(MlKem.Level.ML_KEM_1024.ciphertextSize, i.kemCiphertext!!.size)
    }

    @Test fun new_initiator_pairs_with_old_responder_in_v2() {
        val (i, r) = pair(pa, null, offered = false)!!
        assertFalse(i.v3); assertFalse(r.v3)
    }

    @Test fun old_initiator_pairs_with_new_responder_in_v2() {
        val (i, r) = pair(null, pb, offered = false)!!
        assertFalse(i.v3); assertFalse(r.v3)
    }

    @Test fun v3_confirmation_needs_both_signatures() {
        val (i, r) = pair(pa, pb, offered = true)!!
        val good = i.myConfirmation()
        assertFalse(r.verifyPeerConfirmation(good.copyOfRange(0, 64)))   // Ed25519 alone is not enough
        val bad = good.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertFalse(r.verifyPeerConfirmation(bad))
    }
}
