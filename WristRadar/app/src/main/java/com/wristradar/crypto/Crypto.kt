package com.wristradar.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Thin wrappers over established primitives only:
 *  - X25519 / Ed25519 / HKDF from Google Tink
 *  - AES-256-GCM, HMAC-SHA-256/384, SHA-256/384 from the platform JCA (Conscrypt/BoringSSL)
 *  - ML-KEM and ML-DSA (post-quantum) from BouncyCastle, in MlKem / MlDsa
 * Nothing here is a custom cipher; protocols built on top are documented in SECURITY.md.
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: String, len: Int = 32): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", ikm, salt ?: ByteArray(0), info.toByteArray(Charsets.UTF_8), len)

    /** SHA-384 (suite V4, CNSA 2.0 level). */
    fun sha384(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-384")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** HKDF built on HMAC-SHA-384 (suite V4). Output defaults to 32 bytes = one AES-256 key. */
    fun hkdf384(ikm: ByteArray, salt: ByteArray?, info: String, len: Int = 32): ByteArray =
        Hkdf.computeHkdf("HMACSHA384", ikm, salt ?: ByteArray(0), info.toByteArray(Charsets.UTF_8), len)

    fun hmac384(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA384")
        mac.init(SecretKeySpec(key, "HmacSHA384"))
        return mac.doFinal(data)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun gcmSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plain)
    }

    /** Returns null when authentication fails (tampered, wrong key, wrong AAD). */
    fun gcmOpen(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray?): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) c.updateAAD(aad)
        c.doFinal(ct)
    } catch (e: Exception) {
        null
    }

    fun x25519Private(): ByteArray = X25519.generatePrivateKey()
    fun x25519Public(priv: ByteArray): ByteArray = X25519.publicFromPrivate(priv)
    /** Throws on low-order / invalid public keys (Tink validates). */
    fun x25519(priv: ByteArray, peerPub: ByteArray): ByteArray = X25519.computeSharedSecret(priv, peerPub)

    fun ed25519Verify(pub: ByteArray, sig: ByteArray, data: ByteArray): Boolean = try {
        Ed25519Verify(pub).verify(sig, data); true
    } catch (e: Exception) {
        false
    }

    fun ed25519NewSeed(): Pair<ByteArray, ByteArray> {
        val kp = Ed25519Sign.KeyPair.newKeyPair()
        return kp.privateKey to kp.publicKey
    }

    fun ctEquals(a: ByteArray?, b: ByteArray?): Boolean =
        a != null && b != null && MessageDigest.isEqual(a, b)

    /** Unsigned lexicographic compare, used for deterministic role selection only. */
    fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    fun wipe(b: ByteArray?) { b?.fill(0) }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
