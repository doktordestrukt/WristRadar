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
 * ML-KEM (FIPS 203) from BouncyCastle's maintained lightweight API. It is only ever used in
 * HYBRID with X25519: both shared secrets go into HKDF, so the result is at least as strong as
 * X25519 alone, and also resists "harvest now, decrypt later" if ML-KEM holds up.
 *
 * Two strengths: ML-KEM-768 (NIST category 3; pairing, and sessions with older apps) and
 * ML-KEM-1024 (category 5, the strongest standard set; sessions between MK15+ watches).
 */
object MlKem {
    enum class Level(val publicKeySize: Int, val ciphertextSize: Int, internal val params: MLKEMParameters, val label: String) {
        ML_KEM_768(1184, 1088, MLKEMParameters.ml_kem_768, "ML-KEM-768"),
        ML_KEM_1024(1568, 1568, MLKEMParameters.ml_kem_1024, "ML-KEM-1024");
        companion object { fun forPublicKey(size: Int) = entries.firstOrNull { it.publicKeySize == size } }
    }

    // ML-KEM-768 sizes (pairing + compatibility).
    const val PUBLIC_KEY_SIZE = 1184
    const val CIPHERTEXT_SIZE = 1088
    private val rng = SecureRandom()

    class KeyPair internal constructor(val publicKey: ByteArray, internal val private: MLKEMPrivateKeyParameters, val level: Level)

    fun generate(level: Level = Level.ML_KEM_768): KeyPair {
        val gen = MLKEMKeyPairGenerator()
        gen.init(MLKEMKeyGenerationParameters(rng, level.params))
        val kp = gen.generateKeyPair()
        return KeyPair((kp.public as MLKEMPublicKeyParameters).encoded, kp.private as MLKEMPrivateKeyParameters, level)
    }

    /** Returns (ciphertext, sharedSecret). The level follows the public key's size; throws on anything else. */
    fun encapsulate(publicKey: ByteArray): Pair<ByteArray, ByteArray> {
        val level = requireNotNull(Level.forPublicKey(publicKey.size))
        val enc = MLKEMGenerator(rng).generateEncapsulated(MLKEMPublicKeyParameters(level.params, publicKey))
        return enc.encapsulation to enc.secret
    }

    /** ML-KEM uses implicit rejection: a tampered ciphertext yields an unrelated secret, so the handshake fails later. */
    fun decapsulate(kp: KeyPair, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == kp.level.ciphertextSize)
        return MLKEMExtractor(kp.private).extractSecret(ciphertext)
    }
}
