package com.wristradar.crypto

import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import java.security.SecureRandom

/**
 * ML-DSA-87 (FIPS 204, NIST category 5) post-quantum signatures, from BouncyCastle's maintained
 * lightweight API. Always used IN ADDITION to Ed25519 (both signatures must verify), so the
 * identity check is at least as strong as before and also holds against quantum computers.
 */
object MlDsa {
    const val PUBLIC_KEY_SIZE = 2592
    const val SIGNATURE_SIZE = 4627
    private val params = MLDSAParameters.ml_dsa_87
    private val rng = SecureRandom()

    class KeyPair(val publicKey: ByteArray, val privateEncoded: ByteArray) {
        /** Hedged signing (fresh randomness mixed in), as FIPS 204 recommends. */
        fun sign(msg: ByteArray): ByteArray {
            val s = MLDSASigner()
            s.init(true, ParametersWithRandom(MLDSAPrivateKeyParameters(params, privateEncoded), rng))
            s.update(msg, 0, msg.size)
            return s.generateSignature()
        }
    }

    fun generate(): KeyPair {
        val g = MLDSAKeyPairGenerator()
        g.init(MLDSAKeyGenerationParameters(rng, params))
        val kp = g.generateKeyPair()
        return KeyPair((kp.public as MLDSAPublicKeyParameters).encoded, (kp.private as MLDSAPrivateKeyParameters).encoded)
    }

    fun restore(publicKey: ByteArray, privateEncoded: ByteArray) = KeyPair(publicKey, privateEncoded)

    fun verify(publicKey: ByteArray, sig: ByteArray, msg: ByteArray): Boolean = try {
        if (publicKey.size != PUBLIC_KEY_SIZE || sig.size != SIGNATURE_SIZE) false else {
            val v = MLDSASigner()
            v.init(false, MLDSAPublicKeyParameters(params, publicKey))
            v.update(msg, 0, msg.size)
            v.verifySignature(sig)
        }
    } catch (e: Exception) {
        false
    }
}

/** Post-quantum identity material for one handshake: my ML-DSA key + the friend's public key. */
class PqIdentity(val mine: MlDsa.KeyPair, val partnerPublic: ByteArray)
