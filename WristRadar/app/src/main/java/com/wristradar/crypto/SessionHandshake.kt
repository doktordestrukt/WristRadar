package com.wristradar.crypto

/**
 * Per-connection authenticated, hybrid post-quantum key agreement between two paired watches
 * (SIGMA-I style, additionally keyed by the pairing PSK):
 *
 *   C -> S  HS_HELLO   ePubC | nC | kemPkC                      (fresh X25519 + ML-KEM-768 keys)
 *   S -> C  HS_SERVER  ePubS | nS | kemCt | AEAD_hs(idS | Sig_S("WR-HS-S"|H(t1)))
 *   C -> S  HS_FINISH  AEAD_hs(idC | Sig_C("WR-HS-C"|H(t2)))
 *
 *   ikm = X25519(eC,eS) | ML-KEM secret | psk ;  hs = HKDF(ikm, H(t1), "wr hs key")
 *   c2s = HKDF(ikm, H(t3), "wr c2s") ; s2c = HKDF(ikm, H(t3), "wr s2c")
 *
 * A peer must hold BOTH the pairing PSK and the pinned Ed25519 identity key. Fresh ephemeral
 * keys on every connection give forward secrecy and post-compromise recovery per session.
 */
class SessionKeys(val sendKey: ByteArray, val recvKey: ByteArray)

private val NONCE_S = ByteArray(12).also { it[11] = 1 }
private val NONCE_C = ByteArray(12).also { it[11] = 2 }
private const val HELLO_SIZE = 32 + 16 + MlKem.PUBLIC_KEY_SIZE
private const val SERVER_FIXED = 32 + 16 + MlKem.CIPHERTEXT_SIZE

class HandshakeClient(
    private val identity: Signer,
    private val partnerId: ByteArray,
    private val psk: ByteArray,
) {
    private val eph = Crypto.x25519Private()
    private val ephPub = Crypto.x25519Public(eph)
    private val nonce = Crypto.random(16)
    private val kem = MlKem.generate()

    fun hello(): ByteArray = ephPub + nonce + kem.publicKey

    /** Returns (HS_FINISH payload, keys) or null if the server is not our partner. */
    fun onServer(p: ByteArray): Pair<ByteArray, SessionKeys>? = try {
        if (p.size < SERVER_FIXED + 16) null else {
            val ephS = p.copyOfRange(0, 32)
            val nS = p.copyOfRange(32, 48)
            val ct = p.copyOfRange(48, SERVER_FIXED)
            val enc = p.copyOfRange(SERVER_FIXED, p.size)
            val t1 = "WR-HS-V2".toByteArray() + ephPub + nonce + kem.publicKey + ephS + nS + ct
            val ikm = Crypto.x25519(eph, ephS) + MlKem.decapsulate(kem, ct) + psk
            val hsKey = Crypto.hkdf(ikm, Crypto.sha256(t1), "wr hs key")
            val body = Crypto.gcmOpen(hsKey, NONCE_S, enc, Crypto.sha256(t1))
            if (body == null || body.size != 32 + 64) null else {
                val idS = body.copyOfRange(0, 32)
                val sigS = body.copyOfRange(32, 96)
                if (!Crypto.ctEquals(idS, partnerId) ||
                    !Crypto.ed25519Verify(idS, sigS, "WR-HS-S".toByteArray() + Crypto.sha256(t1))
                ) null else {
                    val t2 = t1 + enc
                    val sigC = identity.sign("WR-HS-C".toByteArray() + Crypto.sha256(t2))
                    val fin = Crypto.gcmSeal(hsKey, NONCE_C, identity.publicKey + sigC, Crypto.sha256(t2))
                    val th = Crypto.sha256(t2 + fin)
                    val keys = SessionKeys(
                        sendKey = Crypto.hkdf(ikm, th, "wr c2s"),
                        recvKey = Crypto.hkdf(ikm, th, "wr s2c"),
                    )
                    Crypto.wipe(ikm); Crypto.wipe(eph)
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
) {
    private var ikm: ByteArray? = null
    private var hsKey: ByteArray? = null
    private var t2: ByteArray? = null

    fun onHello(p: ByteArray): ByteArray? = try {
        if (p.size != HELLO_SIZE) null else {
            val ephC = p.copyOfRange(0, 32)
            val nC = p.copyOfRange(32, 48)
            val kemPkC = p.copyOfRange(48, HELLO_SIZE)
            val eph = Crypto.x25519Private()
            val ephPub = Crypto.x25519Public(eph)
            val nS = Crypto.random(16)
            val (ct, kemSecret) = MlKem.encapsulate(kemPkC)
            val t1 = "WR-HS-V2".toByteArray() + ephC + nC + kemPkC + ephPub + nS + ct
            val k = Crypto.x25519(eph, ephC) + kemSecret + psk
            Crypto.wipe(eph); Crypto.wipe(kemSecret)
            val hk = Crypto.hkdf(k, Crypto.sha256(t1), "wr hs key")
            val sigS = identity.sign("WR-HS-S".toByteArray() + Crypto.sha256(t1))
            val enc = Crypto.gcmSeal(hk, NONCE_S, identity.publicKey + sigS, Crypto.sha256(t1))
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
        val body = Crypto.gcmOpen(hk, NONCE_C, fin, Crypto.sha256(tr)) ?: return null
        if (body.size != 96) return null
        val idC = body.copyOfRange(0, 32)
        val sigC = body.copyOfRange(32, 96)
        if (!Crypto.ctEquals(idC, partnerId)) return null
        if (!Crypto.ed25519Verify(idC, sigC, "WR-HS-C".toByteArray() + Crypto.sha256(tr))) return null
        val th = Crypto.sha256(tr + fin)
        val keys = SessionKeys(
            sendKey = Crypto.hkdf(k, th, "wr s2c"),
            recvKey = Crypto.hkdf(k, th, "wr c2s"),
        )
        Crypto.wipe(k); ikm = null
        return keys
    }
}
