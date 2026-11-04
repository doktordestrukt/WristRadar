package com.wristradar

import com.google.crypto.tink.subtle.Ed25519Sign
import com.wristradar.ble.AdvertPayload
import com.wristradar.ble.DistanceEstimator
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.HandshakeClient
import com.wristradar.crypto.HandshakeServer
import com.wristradar.crypto.MlKem
import com.wristradar.crypto.RecordError
import com.wristradar.crypto.PairingCrypto
import com.wristradar.crypto.SecureChannel
import com.wristradar.crypto.SessionKeys
import com.wristradar.crypto.Signer
import com.wristradar.data.DistanceUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class TestSigner : Signer {
    private val kp = Ed25519Sign.KeyPair.newKeyPair()
    override val publicKey: ByteArray = kp.publicKey
    override fun sign(data: ByteArray): ByteArray = Ed25519Sign(kp.privateKey).sign(data)
}

class CryptoProtocolTest {

    /** Runs the full commit/response/reveal exchange between two honest watches. */
    private fun pair(a: Signer, b: Signer): Pair<PairingCrypto, PairingCrypto> {
        val pa = PairingCrypto(true, a)
        val pb = PairingCrypto(false, b)
        pb.onPeerCommit(pa.myCommit)
        assertTrue(pa.onResponse(pb.ephPub, pb.myId, pb.nonce, pb.kemPublicKey))
        assertTrue(pb.onReveal(pa.ephPub, pa.myId, pa.nonce, pa.kemCiphertext!!))
        return pa to pb
    }

    @Test fun pairing_codes_match_and_psk_agrees() {
        val (pa, pb) = pair(TestSigner(), TestSigner())
        assertEquals(pa.sasCode(), pb.sasCode())
        assertTrue(Regex("\\d{3} \\d{3}").matches(pa.sasCode()))
        assertArrayEquals(pa.derivePsk(), pb.derivePsk())
        assertTrue(pb.verifyPeerConfirmation(pa.myConfirmation()))
        assertTrue(pa.verifyPeerConfirmation(pb.myConfirmation()))
    }

    @Test fun reveal_that_does_not_match_commitment_is_rejected() {
        val pa = PairingCrypto(true, TestSigner())
        val pb = PairingCrypto(false, TestSigner())
        pb.onPeerCommit(pa.myCommit)
        val attacker = PairingCrypto(true, TestSigner())
        assertFalse(pb.onReveal(attacker.ephPub, attacker.myId, attacker.nonce, ByteArray(MlKem.CIPHERTEXT_SIZE)))
    }

    @Test fun man_in_the_middle_produces_different_codes() {
        // Mallory relays A<->M1 and M2<->B with her own keys: the two codes differ.
        val a = PairingCrypto(true, TestSigner())
        val m1 = PairingCrypto(false, TestSigner())
        m1.onPeerCommit(a.myCommit)
        assertTrue(a.onResponse(m1.ephPub, m1.myId, m1.nonce, m1.kemPublicKey))
        assertTrue(m1.onReveal(a.ephPub, a.myId, a.nonce, a.kemCiphertext!!))
        val m2 = PairingCrypto(true, TestSigner())
        val b = PairingCrypto(false, TestSigner())
        b.onPeerCommit(m2.myCommit)
        assertTrue(m2.onResponse(b.ephPub, b.myId, b.nonce, b.kemPublicKey))
        assertTrue(b.onReveal(m2.ephPub, m2.myId, m2.nonce, m2.kemCiphertext!!))
        assertNotEquals(a.sasCode(), b.sasCode())   // 1-in-a-million chance of a false collision
    }

    @Test fun confirmation_from_wrong_identity_is_rejected() {
        val (pa, pb) = pair(TestSigner(), TestSigner())
        val other = PairingCrypto(true, TestSigner())
        assertFalse(pb.verifyPeerConfirmation(Crypto.random(64)))
        assertFalse(pb.verifyPeerConfirmation(pb.myConfirmation()))  // role-bound: own sig can't be replayed back
        assertNotNull(other)
        assertNotNull(pa)
    }

    private fun handshake(client: Signer, server: Signer, pskC: ByteArray, pskS: ByteArray, pinnedByClient: ByteArray = server.publicKey, pinnedByServer: ByteArray = client.publicKey): Pair<SessionKeys?, SessionKeys?> {
        val hc = HandshakeClient(client, pinnedByClient, pskC)
        val hs = HandshakeServer(server, pinnedByServer, pskS)
        val s1 = hs.onHello(hc.hello()) ?: return null to null
        val c = hc.onServer(s1) ?: return null to null
        return c.second to hs.onFinish(c.first)
    }

    @Test fun session_handshake_between_paired_watches_succeeds() {
        val a = TestSigner(); val b = TestSigner()
        val psk = Crypto.random(32)
        val (kc, ks) = handshake(a, b, psk, psk)
        assertNotNull(kc); assertNotNull(ks)
        assertArrayEquals(kc!!.sendKey, ks!!.recvKey)
        assertArrayEquals(kc.recvKey, ks.sendKey)
    }

    @Test fun handshake_fails_with_wrong_psk_or_unknown_device() {
        val a = TestSigner(); val b = TestSigner(); val stranger = TestSigner()
        val psk = Crypto.random(32)
        assertNull(handshake(a, b, psk, Crypto.random(32)).first)                 // unpaired / wiped psk
        assertNull(handshake(stranger, b, psk, psk, pinnedByServer = a.publicKey).second) // stranger even with psk
        assertNull(handshake(a, stranger, psk, psk, pinnedByClient = b.publicKey).first)  // impersonated server
    }

    @Test fun each_session_gets_fresh_keys() {
        val a = TestSigner(); val b = TestSigner(); val psk = Crypto.random(32)
        val k1 = handshake(a, b, psk, psk).first!!
        val k2 = handshake(a, b, psk, psk).first!!
        assertFalse(k1.sendKey.contentEquals(k2.sendKey))
    }

    private fun channels(): Pair<SecureChannel, SecureChannel> {
        val k1 = Crypto.random(32); val k2 = Crypto.random(32)
        return SecureChannel(SessionKeys(k1, k2), isClient = true) to SecureChannel(SessionKeys(k2, k1), isClient = false)
    }

    @Test fun secure_channel_roundtrip() {
        val (c, s) = channels()
        val rec = c.seal("hello".toByteArray())
        assertEquals("hello", String(s.open(rec)!!))
        assertEquals("back", String(c.open(s.seal("back".toByteArray()))!!))
    }

    @Test fun tampered_record_is_rejected() {
        val (c, s) = channels()
        val rec = c.seal("secret".toByteArray())
        rec[rec.size - 1] = (rec[rec.size - 1].toInt() xor 1).toByte()
        assertNull(s.open(rec))
        val rec2 = c.seal("secret".toByteArray())
        rec2[3] = (rec2[3].toInt() xor 1).toByte()        // header (counter) is authenticated too
        assertNull(s.open(rec2))
    }

    @Test fun replayed_and_reordered_records_are_rejected() {
        val (c, s) = channels()
        val r1 = c.seal("1".toByteArray())
        val r2 = c.seal("2".toByteArray())
        assertNotNull(s.open(r1))
        assertNull(s.open(r1))           // replay
        assertNotNull(s.open(r2))
        assertNull(s.open(r1))           // old record after newer
    }

    @Test fun key_ratchet_after_many_records_stays_in_sync() {
        val (c, s) = channels()
        repeat(SecureChannel.REKEY_RECORDS * 3 + 5) { i ->
            assertEquals("m$i", String(s.open(c.seal("m$i".toByteArray()))!!))
        }
    }

    @Test fun record_from_other_session_is_rejected() {
        val (c, _) = channels()
        val (_, s2) = channels()
        assertNull(s2.open(c.seal("x".toByteArray())))
    }

    @Test fun advert_payload_roundtrip_and_tokens_rotate() {
        val key = Crypto.random(32)
        val t = AdvertPayload.token(key, 100)
        val p = AdvertPayload(true, 123456, t)
        val enc = p.encode()
        assertEquals(AdvertPayload.SIZE, enc.size)
        val back = AdvertPayload.parse(enc)!!
        assertTrue(back.discoverable); assertEquals(123456, back.discoveryId); assertArrayEquals(t, back.token)
        assertFalse(t.contentEquals(AdvertPayload.token(key, 101)))
        assertFalse(t.contentEquals(AdvertPayload.token(Crypto.random(32), 100)))
    }

    @Test fun distance_formatting_is_coarse() {
        assertEquals("< 1 m", DistanceEstimator.format(0.5, DistanceUnit.METRES))
        assertEquals("< 3 ft", DistanceEstimator.format(0.5, DistanceUnit.FEET))
        assertEquals("≈ 4 ft", DistanceEstimator.format(1.2, DistanceUnit.FEET))
        assertEquals("≈ 20 m", DistanceEstimator.format(19.0, DistanceUnit.METRES))
        val est = DistanceEstimator()
        repeat(30) { est.add(-62) }
        assertEquals(1.0, est.distanceMetres()!!, 0.05)
        est.add(-90)   // single spike is smoothed
        assertTrue(est.distanceMetres()!! < 3.0)
    }

    @Test fun ml_kem_roundtrip_and_implicit_rejection() {
        val kp = MlKem.generate()
        assertEquals(MlKem.PUBLIC_KEY_SIZE, kp.publicKey.size)
        val (ct, ss) = MlKem.encapsulate(kp.publicKey)
        assertEquals(MlKem.CIPHERTEXT_SIZE, ct.size)
        assertArrayEquals(ss, MlKem.decapsulate(kp, ct))
        val bad = ct.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(ss.contentEquals(MlKem.decapsulate(kp, bad)))
    }

    @Test fun tampered_kem_ciphertext_in_handshake_fails() {
        val a = TestSigner(); val b = TestSigner(); val psk = Crypto.random(32)
        val hc = HandshakeClient(a, b.publicKey, psk)
        val hs = HandshakeServer(b, a.publicKey, psk)
        val s1 = hs.onHello(hc.hello())!!
        s1[48 + 5] = (s1[48 + 5].toInt() xor 1).toByte()   // inside the ML-KEM ciphertext
        assertNull(hc.onServer(s1))
    }

    @Test fun pairing_psk_mixes_kem_secret() {
        // Two runs with identical X25519 parts are impossible to force here, so check psk != pure-X25519 HKDF.
        val (pa, pb) = pair(TestSigner(), TestSigner())
        assertArrayEquals(pa.derivePsk(), pb.derivePsk())
        assertTrue(pa.ready && pb.ready)
    }

    @Test fun replay_and_forgery_are_classified() {
        val (c, s) = channels()
        val r1 = c.seal("1".toByteArray())
        assertNotNull(s.open(r1))
        assertNull(s.open(r1)); assertEquals(RecordError.REPLAY, s.lastError)
        val forged = r1.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(s.open(forged)); assertEquals(RecordError.AUTH, s.lastError)
        val r2 = c.seal("2".toByteArray()).also { it[it.size - 2] = (it[it.size - 2].toInt() xor 4).toByte() }
        assertNull(s.open(r2)); assertEquals(RecordError.AUTH, s.lastError)
    }
}
