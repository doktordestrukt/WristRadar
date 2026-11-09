package com.wristradar.crypto

/**
 * Per-connection authenticated, hybrid post-quantum key agreement between two paired watches
 * (SIGMA-I style, additionally keyed by the pairing PSK):
 *
 *   C -> S  HS_HELLO   [04] | ePubC | nC | kemPkC                (fresh X25519 + ML-KEM keys)
 *   S -> C  HS_SERVER  ePubS | nS | kemCt | AEAD_hs(idS | Sig_S(...) [| PqSig_S(...)])
 *   C -> S  HS_FINISH  AEAD_hs(idC | Sig_C(...) [| PqSig_C(...)])
 *
 *   ikm = X25519(eC,eS) | ML-KEM secret | psk ;  hs = KDF(ikm, H(t1), "wr hs key")
 *   c2s = KDF(ikm, H(t3), "wr c2s") ; s2c = KDF(ikm, H(t3), "wr s2c")
 *
 * Suites (the client picks; the label is part of the transcript, so nobody in the middle can
 * swap one for another without breaking the signatures):
 *   V2  ML-KEM-768,  SHA-256, HKDF-SHA-256, Ed25519                       (every version)
 *   V3  ML-KEM-1024, SHA-256, HKDF-SHA-256, Ed25519                       (MK15+)
 *   V4  ML-KEM-1024, SHA-384, HKDF-SHA-384, Ed25519 AND ML-DSA-87         (MK16+, CNSA 2.0 style)
 *   V5  as V4, plus a fresh key for EVERY message (symmetric hash ratchet)  (MK17+)
 *
 * A peer must hold BOTH the pairing PSK and the pinned identity key(s). Fresh ephemeral keys on
 * every connection give forward secrecy and post-compromise recovery per session.
 */
enum class Suite(val label: String, val kem: MlKem.Level, val wide: Boolean, val title: String, val perMessage: Boolean = false, internal val mark: Byte? = null) {
    V2("WR-HS-V2", MlKem.Level.ML_KEM_768, false, "X25519 + ML-KEM-768, Ed25519, SHA-256"),
    V3("WR-HS-V3", MlKem.Level.ML_KEM_1024, false, "X25519 + ML-KEM-1024, Ed25519, SHA-256"),
    V4("WR-HS-V4", MlKem.Level.ML_KEM_1024, true, "X25519 + ML-KEM-1024, Ed25519 + ML-DSA-87, SHA-384", mark = 0x04),
    V5("WR-HS-V5", MlKem.Level.ML_KEM_1024, true, "X25519 + ML-KEM-1024, Ed25519 + ML-DSA-87, SHA-384, new key every message", perMessage = true, mark = 0x05);

    internal fun h(vararg parts: ByteArray) = if (wide) Crypto.sha384(*parts) else Crypto.sha256(*parts)
    internal fun kdf(ikm: ByteArray, salt: ByteArray, info: String) = if (wide) Crypto.hkdf384(ikm, salt, info) else Crypto.hkdf(ikm, salt, info)
    internal val helloSize get() = (if (mark != null) 1 else 0) + 48 + kem.publicKeySize
    internal val bodySize get() = 32 + 64 + (if (wide) MlDsa.SIGNATURE_SIZE else 0)

    companion object {
        /** Works out the suite from a HELLO's size (and version byte for V4). */
        internal fun ofHello(p: ByteArray): Suite? = when {
            p.size == V5.helloSize && p[0] == V5.mark -> V5
            p.size == V4.helloSize && p[0] == V4.mark -> V4
            p.size == V3.helloSize -> V3
            p.size == V2.helloSize -> V2
            else -> null
        }
    }
}

class SessionKeys(
    val sendKey: ByteArray,
    val recvKey: ByteArray,
    val kem: MlKem.Level = MlKem.Level.ML_KEM_768,
    val suite: Suite = if (kem == MlKem.Level.ML_KEM_1024) Suite.V3 else Suite.V2,
)

private val NONCE_S = ByteArray(12).also { it[11] = 1 }
private val NONCE_C = ByteArray(12).also { it[11] = 2 }

class HandshakeClient(
    private val identity: Signer,
    private val partnerId: ByteArray,
    private val psk: ByteArray,
    level: MlKem.Level = MlKem.Level.ML_KEM_768,
    /** With ML-KEM-1024 and post-quantum identities, the client uses suite V4 (V5 with [perMessage]). */
    private val pq: PqIdentity? = null,
    perMessage: Boolean = false,
) {
    val suite: Suite = when {
        level == MlKem.Level.ML_KEM_1024 && pq != null && perMessage -> Suite.V5
        level == MlKem.Level.ML_KEM_1024 && pq != null -> Suite.V4
        level == MlKem.Level.ML_KEM_1024 -> Suite.V3
        else -> Suite.V2
    }
    val level: MlKem.Level get() = suite.kem
    private val eph = Crypto.x25519Private()
    private val ephPub = Crypto.x25519Public(eph)
    private val nonce = Crypto.random(16)
    private val kem = MlKem.generate(suite.kem)
    private val serverFixed = 32 + 16 + suite.kem.ciphertextSize
    /** True when everything checked out except the ML-DSA-87 signature (key mix-up, not an intruder). */
    var failedOnlyPq = false
        private set

    fun hello(): ByteArray {
        val body = ephPub + nonce + kem.publicKey
        val m = suite.mark
        return if (m != null) byteArrayOf(m) + body else body
    }

    /** Returns (HS_FINISH payload, keys) or null if the server is not our partner. */
    fun onServer(p: ByteArray): Pair<ByteArray, SessionKeys>? = try {
        if (p.size < serverFixed + 16) null else {
            val ephS = p.copyOfRange(0, 32)
            val nS = p.copyOfRange(32, 48)
            val ct = p.copyOfRange(48, serverFixed)
            val enc = p.copyOfRange(serverFixed, p.size)
            val t1 = suite.label.toByteArray() + ephPub + nonce + kem.publicKey + ephS + nS + ct
            val ikm = Crypto.x25519(eph, ephS) + MlKem.decapsulate(kem, ct) + psk
            val hsKey = suite.kdf(ikm, suite.h(t1), "wr hs key")
            val body = Crypto.gcmOpen(hsKey, NONCE_S, enc, suite.h(t1))
            if (body == null || body.size != suite.bodySize) null else {
                val idS = body.copyOfRange(0, 32)
                val sigS = body.copyOfRange(32, 96)
                val okClassic = Crypto.ctEquals(idS, partnerId) && Crypto.ed25519Verify(idS, sigS, "WR-HS-S".toByteArray() + suite.h(t1))
                val okPq = !suite.wide || MlDsa.verify(pq!!.partnerPublic, body.copyOfRange(96, body.size), "WR-HS-S-PQ".toByteArray() + suite.h(t1))
                failedOnlyPq = okClassic && !okPq
                if (!okClassic || !okPq) null else {
                    val t2 = t1 + enc
                    val sigC = identity.sign("WR-HS-C".toByteArray() + suite.h(t2))
                    val pqSigC = if (suite.wide) pq!!.mine.sign("WR-HS-C-PQ".toByteArray() + suite.h(t2)) else ByteArray(0)
                    val fin = Crypto.gcmSeal(hsKey, NONCE_C, identity.publicKey + sigC + pqSigC, suite.h(t2))
                    val th = suite.h(t2 + fin)
                    val keys = SessionKeys(
                        sendKey = suite.kdf(ikm, th, "wr c2s"),
                        recvKey = suite.kdf(ikm, th, "wr s2c"),
                        kem = suite.kem, suite = suite,
                    )
                    Crypto.wipe(ikm); Crypto.wipe(eph); Crypto.wipe(hsKey)
                    fin to keys
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

class HandshakeServer(
    private val identity: Signer,
    private val partnerId: ByteArray,
    private val psk: ByteArray,
    /** Needed only to accept V4 hellos; without it a V4 hello is refused and the client falls back. */
    private val pq: PqIdentity? = null,
) {
    private var ikm: ByteArray? = null
    private var hsKey: ByteArray? = null
    private var t2: ByteArray? = null
    private var suite = Suite.V2
    /** True when everything checked out except the ML-DSA-87 signature (key mix-up, not an intruder). */
    var failedOnlyPq = false
        private set

    /** Accepts every suite this app knows: older apps send V2, MK15 V3, MK16+ V4. */
    fun onHello(p: ByteArray): ByteArray? = try {
        val su = Suite.ofHello(p)
        if (su == null || (su.wide && pq == null)) null else {
            suite = su
            val q = if (su.mark != null) p.copyOfRange(1, p.size) else p
            val ephC = q.copyOfRange(0, 32)
            val nC = q.copyOfRange(32, 48)
            val kemPkC = q.copyOfRange(48, q.size)
            val eph = Crypto.x25519Private()
            val ephPub = Crypto.x25519Public(eph)
            val nS = Crypto.random(16)
            val (ct, kemSecret) = MlKem.encapsulate(kemPkC)
            val t1 = su.label.toByteArray() + ephC + nC + kemPkC + ephPub + nS + ct
            val k = Crypto.x25519(eph, ephC) + kemSecret + psk
            Crypto.wipe(eph); Crypto.wipe(kemSecret)
            val hk = su.kdf(k, su.h(t1), "wr hs key")
            val sigS = identity.sign("WR-HS-S".toByteArray() + su.h(t1))
            val pqSigS = if (su.wide) pq!!.mine.sign("WR-HS-S-PQ".toByteArray() + su.h(t1)) else ByteArray(0)
            val enc = Crypto.gcmSeal(hk, NONCE_S, identity.publicKey + sigS + pqSigS, su.h(t1))
            ikm = k; hsKey = hk; t2 = t1 + enc
            ephPub + nS + ct + enc
        }
    } catch (e: Exception) {
        null
    }

    fun onFinish(fin: ByteArray): SessionKeys? {
        val k = ikm ?: return null
        val hk = hsKey ?: return null
        val tr = t2 ?: return null
        val su = suite
        val body = Crypto.gcmOpen(hk, NONCE_C, fin, su.h(tr)) ?: return null
        if (body.size != su.bodySize) return null
        val idC = body.copyOfRange(0, 32)
        val sigC = body.copyOfRange(32, 96)
        if (!Crypto.ctEquals(idC, partnerId)) return null
        if (!Crypto.ed25519Verify(idC, sigC, "WR-HS-C".toByteArray() + su.h(tr))) return null
        if (su.wide && !MlDsa.verify(pq!!.partnerPublic, body.copyOfRange(96, body.size), "WR-HS-C-PQ".toByteArray() + su.h(tr))) { failedOnlyPq = true; return null }
        val th = su.h(tr + fin)
        val keys = SessionKeys(
            sendKey = su.kdf(k, th, "wr s2c"),
            recvKey = su.kdf(k, th, "wr c2s"),
            kem = su.kem, suite = su,
        )
        Crypto.wipe(k); Crypto.wipe(hk); ikm = null; hsKey = null; t2 = null
        return keys
    }
}
