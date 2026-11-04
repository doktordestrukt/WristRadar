package com.wristradar.data

import android.content.Context
import android.util.Base64
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.KeystoreVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The single trusted partner (V1 supports exactly one). */
class Partner(
    val identityKey: ByteArray,
    val psk: ByteArray,
    val pairedAt: Long,
) {
    /** Key used to compute the rolling advertising token a given watch broadcasts. */
    fun advertKeyFor(advertiserId: ByteArray): ByteArray =
        Crypto.hkdf(psk, advertiserId, "wr advert token", 32)
}

class TrustStore(context: Context, private val vault: KeystoreVault) {
    private val prefs = context.getSharedPreferences("wr_trust", Context.MODE_PRIVATE)
    private val _partner = MutableStateFlow(load())
    val partner: StateFlow<Partner?> = _partner.asStateFlow()

    private val _lastConnected = MutableStateFlow(prefs.getLong("last_connected", 0L))
    val lastConnected: StateFlow<Long> = _lastConnected.asStateFlow()

    private fun load(): Partner? {
        val id = prefs.getString("peer_id", null) ?: return null
        val pskEnc = prefs.getString("psk_enc", null) ?: return null
        val psk = vault.decrypt(Base64.decode(pskEnc, Base64.NO_WRAP), "wr-psk") ?: return null
        return Partner(Base64.decode(id, Base64.NO_WRAP), psk, prefs.getLong("paired_at", 0L))
    }

    fun save(identityKey: ByteArray, psk: ByteArray) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("peer_id", Base64.encodeToString(identityKey, Base64.NO_WRAP))
            .putString("psk_enc", Base64.encodeToString(vault.encrypt(psk, "wr-psk"), Base64.NO_WRAP))
            .putLong("paired_at", now)
            .commit()
        _partner.value = Partner(identityKey, psk, now)
    }

    fun markConnected() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_connected", now).apply()
        _lastConnected.value = now
    }

    private val _partnerLastSeen = MutableStateFlow(prefs.getLong("partner_last_seen", 0L))
    /** Last moment the partner was known to be online (connected). */
    val partnerLastSeen: StateFlow<Long> = _partnerLastSeen.asStateFlow()

    fun markPartnerSeen(t: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("partner_last_seen", t).apply()
        _partnerLastSeen.value = t
    }

    /** Monotonic outgoing message sequence number (survives restarts). */
    @Synchronized
    fun nextSequence(): Long {
        val n = prefs.getLong("seq", 0L) + 1
        prefs.edit().putLong("seq", n).apply()
        return n
    }

    fun clear() {
        _partner.value?.let { Crypto.wipe(it.psk) }
        prefs.edit().clear().commit()
        _partner.value = null
        _lastConnected.value = 0L
        _partnerLastSeen.value = 0L
    }
}
