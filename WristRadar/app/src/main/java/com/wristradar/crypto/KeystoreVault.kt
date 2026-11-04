package com.wristradar.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Data-at-rest protection. A non-exportable AES-256-GCM key lives in the Android Keystore
 * (TEE-backed on Galaxy Watch). Everything sensitive written to disk (identity seed, pairing
 * secret, message bodies, received images, partner avatar) is wrapped with it.
 * Blob layout: iv(12) | ciphertext+tag.
 */
class KeystoreVault(private val alias: String = "wristradar_vault_v1") {

    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray, aad: String? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        if (aad != null) c.updateAAD(aad.toByteArray())
        val ct = c.doFinal(plain)
        return c.iv + ct
    }

    fun decrypt(blob: ByteArray, aad: String? = null): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        if (aad != null) c.updateAAD(aad.toByteArray())
        c.doFinal(blob, 12, blob.size - 12)
    } catch (e: Exception) {
        null
    }
}
