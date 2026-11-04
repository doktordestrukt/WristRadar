package com.wristradar.crypto

import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom

/**
 * ML-KEM-768 (FIPS 203) from BouncyCastle's maintained lightweight API. It is only ever used in
 * HYBRID with X25519: both shared secrets go into HKDF, so the result is at least as strong as
 * X25519 alone, and also resists "harvest now, decrypt later" if ML-KEM holds up.
 */
object MlKem {
    const val PUBLIC_KEY_SIZE = 1184
    const val CIPHERTEXT_SIZE = 1088
    private val params = MLKEMParameters.ml_kem_768
    private val rng = SecureRandom()

    class KeyPair internal constructor(val publicKey: ByteArray, internal val private: MLKEMPrivateKeyParameters)

    fun generate(): KeyPair {
        val gen = MLKEMKeyPairGenerator()
        gen.init(MLKEMKeyGenerationParameters(rng, params))
        val kp = gen.generateKeyPair()
        return KeyPair((kp.public as MLKEMPublicKeyParameters).encoded, kp.private as MLKEMPrivateKeyParameters)
    }

    /** Returns (ciphertext, sharedSecret). Throws on malformed public keys. */
    fun encapsulate(publicKey: ByteArray): Pair<ByteArray, ByteArray> {
        require(publicKey.size == PUBLIC_KEY_SIZE)
        val enc = MLKEMGenerator(rng).generateEncapsulated(MLKEMPublicKeyParameters(params, publicKey))
        return enc.encapsulation to enc.secret
    }

    /** ML-KEM uses implicit rejection: a tampered ciphertext yields an unrelated secret, so the handshake fails later. */
    fun decapsulate(kp: KeyPair, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == CIPHERTEXT_SIZE)
        return MLKEMExtractor(kp.private).extractSecret(ciphertext)
    }
}
