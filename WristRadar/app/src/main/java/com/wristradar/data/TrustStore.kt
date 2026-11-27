package com.wristradar.data

import android.content.Context
import android.util.Base64
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.KeystoreVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A trusted, paired watch. Until MK18 there was exactly one ("the partner"); from MK19 there can be
 * several contacts - the first one paired stays the primary [TrustStore.partner].
 */
class Partner(
    val identityKey: ByteArray,
    val psk: ByteArray,
    val pairedAt: Long,
) {
    /** Stable contact id (hex of the identity key). */
    val id: String get() = identityKey.joinToString("") { "%02x".format(it) }

    /** Key used to compute the rolling advertising token a given watch broadcasts. */
    fun advertKeyFor(advertiserId: ByteArray): ByteArray =
        Crypto.hkdf(psk, advertiserId, "wr advert token", 32)
}

class TrustStore(context: Context, private val vault: KeystoreVault) {
    private val prefs = context.getSharedPreferences("wr_trust", Context.MODE_PRIVATE)
    private val _partner = MutableStateFlow(load())
    val partner: StateFlow<Partner?> = _partner.asStateFlow()

    // ---------------------------------------------------------------- MK19: more contacts

    /** Contacts paired after the primary one. */
    private val _extras = MutableStateFlow(loadExtras())

    /** Every paired watch, primary first. */
    private val _contacts = MutableStateFlow(listOfNotNull(_partner.value) + _extras.value)
    val contacts: StateFlow<List<Partner>> = _contacts.asStateFlow()

    private fun refreshContacts() { _contacts.value = listOfNotNull(_partner.value) + _extras.value }

    fun contact(id: String): Partner? = _contacts.value.firstOrNull { it.id == id }
    fun isPrimary(id: String) = _partner.value?.id == id

    private fun loadExtras(): List<Partner> = runCatching {
        val arr = JSONArray(prefs.getString("extras", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val idKey = Base64.decode(o.getString("id"), Base64.NO_WRAP)
            val psk = vault.decrypt(Base64.decode(o.getString("psk"), Base64.NO_WRAP), "wr-psk-extra") ?: return@mapNotNull null
            Partner(idKey, psk, o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun saveExtras(list: List<Partner>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", Base64.encodeToString(p.identityKey, Base64.NO_WRAP))
                .put("psk", Base64.encodeToString(vault.encrypt(p.psk, "wr-psk-extra"), Base64.NO_WRAP))
                .put("at", p.pairedAt))
        }
        prefs.edit().putString("extras", arr.toString()).commit()
        _extras.value = list
        refreshContacts()
    }

    /** Pairing finished: the first watch becomes the primary contact, later ones are added. */
    fun addContact(identityKey: ByteArray, psk: ByteArray) {
        val primary = _partner.value
        if (primary == null || primary.identityKey.contentEquals(identityKey)) { save(identityKey, psk); return }
        val now = System.currentTimeMillis()
        saveExtras(_extras.value.filterNot { it.identityKey.contentEquals(identityKey) } + Partner(identityKey, psk, now))
        prefs.edit().remove("pq_" + Partner(identityKey, psk, now).id).apply()
    }

    /** Removes one contact. If it was the primary, the next contact becomes the primary. */
    fun removeContact(id: String) {
        if (isPrimary(id)) {
            val next = _extras.value.firstOrNull()
            val nextPq = next?.let { contactPq(it.id) }
            val nextName = next?.let { contactName(it.id) }
            clearPrimaryOnly()
            if (next != null) {
                save(next.identityKey, next.psk)
                nextPq?.let { savePartnerPq(it) }
                nextName?.let { setContactName(next.id, it) }
                saveExtras(_extras.value.drop(1))
            }
        } else {
            saveExtras(_extras.value.filterNot { it.id == id })
            prefs.edit().remove("pq_$id").remove("name_$id").apply()
        }
        refreshContacts()
    }

    /** Display name a contact sent us (the primary's name also lives in ProfileStore). */
    fun contactName(id: String): String? = prefs.getString("name_$id", null)
    fun setContactName(id: String, name: String) { prefs.edit().putString("name_$id", name).apply(); _names.value = _names.value + (id to name) }
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Names by contact id (updates when a contact sends a new profile). */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** A contact's ML-DSA-87 key (primary: [partnerPq]). */
    fun contactPq(id: String): ByteArray? =
        if (isPrimary(id)) partnerPq
        else prefs.getString("pq_$id", null)?.let { vault.decrypt(Base64.decode(it, Base64.NO_WRAP), "wr-peer-mldsa87:$id") }

    /** First key wins; can't be swapped later without pairing again. */
    fun saveContactPq(id: String, pub: ByteArray): Boolean {
        if (isPrimary(id)) return savePartnerPq(pub)
        val cur = contactPq(id)
        if (cur != null) return cur.contentEquals(pub)
        prefs.edit().putString("pq_$id", Base64.encodeToString(vault.encrypt(pub, "wr-peer-mldsa87:$id"), Base64.NO_WRAP)).commit()
        return true
    }

    private fun clearPrimaryOnly() {
        _partner.value?.let { Crypto.wipe(it.psk) }
        prefs.edit().remove("peer_id").remove("psk_enc").remove("paired_at").remove("peer_pq").commit()
        pqCache = null; pqLoaded = true
        _partner.value = null
    }

    init {
        _names.value = _contacts.value.mapNotNull { c -> contactName(c.id)?.let { c.id to it } }.toMap()
    }

    private val _lastConnected = MutableStateFlow(prefs.getLong("last_connected", 0L))
    val lastConnected: StateFlow<Long> = _lastConnected.asStateFlow()

    private fun load(): Partner? {
        val id = prefs.getString("peer_id", null) ?: return null
        val pskEnc = prefs.getString("psk_enc", null) ?: return null
        val psk = vault.decrypt(Base64.decode(pskEnc, Base64.NO_WRAP), "wr-psk") ?: return null
        return Partner(Base64.decode(id, Base64.NO_WRAP), psk, prefs.getLong("paired_at", 0L))
    }

    /**
     * The friend's ML-DSA-87 public key, learned over an already-authenticated encrypted session
     * (first one wins; it can't be swapped later without pairing again). Keystore-sealed so a
     * changed file is detected.
     */
    @Volatile private var pqCache: ByteArray? = null
    @Volatile private var pqLoaded = false

    val partnerPq: ByteArray?
        get() {
            if (!pqLoaded) {
                pqCache = prefs.getString("peer_pq", null)?.let { vault.decrypt(Base64.decode(it, Base64.NO_WRAP), "wr-peer-mldsa87") }
                pqLoaded = true
            }
            return pqCache
        }

    fun savePartnerPq(pub: ByteArray): Boolean {
        val cur = partnerPq
        if (cur != null) return cur.contentEquals(pub)
        prefs.edit().putString("peer_pq", Base64.encodeToString(vault.encrypt(pub, "wr-peer-mldsa87"), Base64.NO_WRAP)).commit()
        pqCache = pub; pqLoaded = true
        return true
    }

    fun save(identityKey: ByteArray, psk: ByteArray) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .remove("peer_pq")
            .also { pqCache = null; pqLoaded = true }
            .putString("peer_id", Base64.encodeToString(identityKey, Base64.NO_WRAP))
            .putString("psk_enc", Base64.encodeToString(vault.encrypt(psk, "wr-psk"), Base64.NO_WRAP))
            .putLong("paired_at", now)
            .commit()
        _partner.value = Partner(identityKey, psk, now)
        refreshContacts()
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
        _extras.value = emptyList()
        refreshContacts()
    }
}
