package com.wristradar.data

import android.content.Context
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.KeystoreVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App lock PIN (4-6 digits).
 *
 * Storage: salt + PBKDF2-HMAC-SHA256 hash (60 000 rounds), and that record is itself sealed with
 * the hardware-backed Android Keystore key. Copying the app's files to another device is useless:
 * without this watch's Keystore the hash can't even be read, so the PIN can't be guessed offline.
 * On the watch, wrong guesses are slowed down (5 wrong = 30 s wait, doubling up to 10 min).
 */
class AppLock(context: Context, private val vault: KeystoreVault) {
    private val prefs = context.getSharedPreferences("wr_lock", Context.MODE_PRIVATE)

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    val isSet: Boolean get() = prefs.contains(KEY_RECORD)

    /** Minutes after unlocking before the PIN is asked again. */
    var relockMinutes: Int
        get() = prefs.getInt("relock_min", 30)
        set(v) { prefs.edit().putInt("relock_min", v.coerceIn(0, 240)).apply() }

    private var lastUnlock: Long
        get() = prefs.getLong("last_unlock", 0L)
        set(v) { prefs.edit().putLong("last_unlock", v).apply() }

    /** Wrong-PIN slow-down: time (ms) until the next try is allowed, 0 = now. */
    fun waitMs(): Long = (prefs.getLong("lockout_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    init { if (isSet) _locked.value = true }

    fun setPin(pin: String): Boolean {
        if (!valid(pin)) return false
        val salt = Crypto.random(16)
        val record = salt + hash(pin, salt)
        prefs.edit().putString(KEY_RECORD, android.util.Base64.encodeToString(vault.encrypt(record, AAD), android.util.Base64.NO_WRAP))
            .putInt("fails", 0).putLong("lockout_until", 0L).apply()
        markUnlocked()
        return true
    }

    fun remove() {
        prefs.edit().remove(KEY_RECORD).putInt("fails", 0).putLong("lockout_until", 0L).apply()
        _locked.value = false
    }

    /** Checks a PIN. On success the app unlocks; on failure the wait grows. */
    fun verify(pin: String): Boolean {
        if (waitMs() > 0) return false
        val ok = check(pin)
        if (ok) {
            prefs.edit().putInt("fails", 0).putLong("lockout_until", 0L).apply()
            markUnlocked()
        } else {
            val fails = prefs.getInt("fails", 0) + 1
            val wait = if (fails >= 5) minOf(600_000L, 30_000L shl (fails - 5).coerceAtMost(5)) else 0L
            prefs.edit().putInt("fails", fails).putLong("lockout_until", System.currentTimeMillis() + wait).apply()
        }
        return ok
    }

    /** Same check without unlocking or counting (for "change / remove PIN"). */
    fun check(pin: String): Boolean {
        val enc = prefs.getString(KEY_RECORD, null) ?: return true
        val record = vault.decrypt(android.util.Base64.decode(enc, android.util.Base64.NO_WRAP), AAD) ?: return false
        if (record.size != 16 + 32) return false
        return Crypto.ctEquals(hash(pin, record.copyOfRange(0, 16)), record.copyOfRange(16, 48))
    }

    /**
     * Called when the app comes to the front ([foreground] = true) and every so often while it's
     * open. "Every time" (0 minutes) only locks on coming to the front, not while you're using it.
     */
    fun checkTimeout(foreground: Boolean) {
        if (!isSet || _locked.value) return
        val mins = relockMinutes
        if (mins == 0) { if (foreground) _locked.value = true; return }
        if (System.currentTimeMillis() - lastUnlock >= mins * 60_000L) _locked.value = true
    }

    fun lockNow() { if (isSet) _locked.value = true }

    private fun markUnlocked() {
        lastUnlock = System.currentTimeMillis()
        _locked.value = false
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ROUNDS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    companion object {
        private const val KEY_RECORD = "pin_record"
        private const val AAD = "wr-app-lock-v1"
        private const val ROUNDS = 60_000
        fun valid(pin: String) = pin.length in 4..6 && pin.all { it in '0'..'9' }
    }
}
