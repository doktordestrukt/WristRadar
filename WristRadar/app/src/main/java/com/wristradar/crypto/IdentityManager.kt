package com.wristradar.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Sign

/**
 * Long-term device identity: an Ed25519 key pair generated on first launch.
 * The 32-byte private seed is stored only Keystore-encrypted; it is never transmitted.
 * The username is NOT part of the identity - changing it never touches these keys.
 */
class IdentityManager(context: Context, private val vault: KeystoreVault) : Signer {

    private val prefs = context.getSharedPreferences("wr_identity", Context.MODE_PRIVATE)
    private val seed: ByteArray
    override val publicKey: ByteArray

    init {
        val storedSeed = prefs.getString(KEY_SEED, null)
            ?.let { vault.decrypt(Base64.decode(it, Base64.NO_WRAP), AAD) }
        val storedPub = prefs.getString(KEY_PUB, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (storedSeed != null && storedPub != null) {
            seed = storedSeed
            publicKey = storedPub
        } else {
            val (s, p) = Crypto.ed25519NewSeed()
            seed = s
            publicKey = p
            prefs.edit()
                .putString(KEY_SEED, Base64.encodeToString(vault.encrypt(s, AAD), Base64.NO_WRAP))
                .putString(KEY_PUB, Base64.encodeToString(p, Base64.NO_WRAP))
                .apply()
        }
    }

    override fun sign(data: ByteArray): ByteArray = Ed25519Sign(seed).sign(data)

    /** Short human-readable fingerprint, e.g. for the About / partner screens. */
    fun fingerprint(): String = fingerprintOf(publicKey)

    companion object {
        private const val KEY_SEED = "seed_enc"
        private const val KEY_PUB = "pub"
        private const val AAD = "wr-identity-seed"

        fun fingerprintOf(pub: ByteArray): String =
            Crypto.sha256(pub).copyOf(6).toHex().uppercase().chunked(4).joinToString(" ")
    }
}
