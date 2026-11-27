package com.wristradar.core

import android.content.Context
import android.util.Base64
import com.wristradar.AppGraph
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.toHex
import com.wristradar.data.db.MessageEntity
import com.wristradar.data.db.MsgKind
import com.wristradar.data.db.MsgStatus
import com.wristradar.protocol.A
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Group chats (MK19). See GROUPS_DESIGN.md.
 *
 * * A group's roster (members' identity keys + names) is signed by its creator; a watch only
 *   accepts a roster that lists it, from one of its contacts.
 * * Every group message is signed (Ed25519) by its author over groupId | msgId | sentAt | text,
 *   and checked against the roster - a member passing a message on cannot change or forge it.
 * * Delivery floods over the normal 1:1 encrypted sessions with de-duplication, so members that
 *   aren't paired with each other still get everything through a member they share.
 */
class GroupHub(private val g: AppGraph, private val engine: RadarEngine) {

    data class Member(val key: ByteArray, val name: String) { val id: String get() = key.toHex() }

    data class Group(
        val id: String,                 // hex of 16 random bytes
        val name: String,
        val members: List<Member>,
        val creator: String,            // contact id (hex key) of the creator
        val version: Long,
        val signed: ByteArray,          // the exact signed roster bytes (re-sent as-is)
    ) {
        val conv: String get() = "g:$id"
        fun has(id: String) = members.any { it.id == id }
        fun nameOf(id: String) = members.firstOrNull { it.id == id }?.name
    }

    private val prefs = g.appContext.getSharedPreferences("wr_groups", Context.MODE_PRIVATE)
    private val _groups = MutableStateFlow(load())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    fun group(id: String) = _groups.value.firstOrNull { it.id == id }
    private val myId: String get() = g.identity.publicKey.toHex()

    // ---------------------------------------------------------------- create / roster

    /** Creates a group with me + the given contacts and sends it to them. */
    fun create(name: String, contactIds: List<String>): Group? {
        val contacts = contactIds.mapNotNull { g.trust.contact(it) }
        if (contacts.isEmpty()) return null
        val members = listOf(Member(g.identity.publicKey, g.profile.myName.value)) +
            contacts.map { Member(it.identityKey, g.trust.contactName(it.id) ?: if (g.trust.isPrimary(it.id)) g.profile.partnerName.value ?: "Friend" else "Friend") }
        val gid = Crypto.random(16).toHex()
        val signed = signRoster(gid, name.trim().take(40).ifBlank { "Group" }, members, System.currentTimeMillis())
        val grp = parseRoster(signed) ?: return null
        save(grp)
        engine.scope.launch {
            engine.messenger.insertInfo("👥 You created ${grp.name}", grp.conv)
            grp.members.filter { it.id != myId }.forEach { m -> engine.sessionFor(m.id)?.send(A.GROUP, grp.signed) }
        }
        return grp
    }

    private fun signRoster(gid: String, name: String, members: List<Member>, version: Long): ByteArray {
        val body = Out().raw(gid.hexBytes()).str(name).i(members.size)
        members.forEach { body.raw(it.key).str(it.name.take(24)) }
        body.raw(g.identity.publicKey).l(version)
        val b = body.build()
        val sig = g.identity.sign("WR-GROUP".toByteArray() + b)
        return b + sig
    }

    /** Verifies and decodes a signed roster (creator must be in it). */
    private fun parseRoster(p: ByteArray): Group? = runCatching {
        if (p.size < 64 + 16) return null
        val body = p.copyOfRange(0, p.size - 64)
        val sig = p.copyOfRange(p.size - 64, p.size)
        val r = In(body)
        val gid = r.raw(16).toHex()
        val name = r.str(120).take(40)
        val n = r.i().coerceIn(0, 16)
        val members = List(n) { Member(r.raw(32), r.str(100).take(24)) }
        val creator = r.raw(32)
        val version = r.l()
        if (!Crypto.ed25519Verify(creator, sig, "WR-GROUP".toByteArray() + body)) return null
        if (members.none { it.key.contentEquals(creator) }) return null
        Group(gid, name, members, creator.toHex(), version, p)
    }.getOrNull()

    // ---------------------------------------------------------------- messages

    /** Sends a group message: signed by me, given to every member I'm connected to. */
    fun sendText(groupId: String, text: String): Boolean {
        val grp = group(groupId) ?: return false
        val msgId = UUID.randomUUID()
        val sentAt = System.currentTimeMillis()
        val pkt = packText(grp.id, msgId, g.identity.publicKey, sentAt, text, sign = true)
        engine.scope.launch {
            val id = msgId.toString()
            g.messages.upsert(
                MessageEntity(
                    id = id, outgoing = true, kind = MsgKind.TEXT, bodyEnc = g.messages.encryptBody(id, text),
                    senderSeq = 0, sentAt = sentAt, localTime = sentAt, status = MsgStatus.PENDING,
                    conv = grp.conv, sender = myId,
                )
            )
            remember(id, pkt)
            val sent = fanOut(grp, pkt, except = null)
            if (sent > 0) g.messages.setStatus(id, MsgStatus.DELIVERED)
        }
        return true
    }

    private fun packText(gid: String, msgId: UUID, origin: ByteArray, sentAt: Long, text: String, sign: Boolean, sig: ByteArray? = null): ByteArray {
        val signedPart = "WR-GTEXT".toByteArray() + gid.hexBytes() + Out().uuid(msgId).l(sentAt).build() + text.toByteArray()
        val s = sig ?: if (sign) g.identity.sign(signedPart) else ByteArray(64)
        return Out().raw(gid.hexBytes()).uuid(msgId).raw(origin).l(sentAt).str(text).raw(s).build()
    }

    /** Gives a packet to every connected member except [except]. Returns how many got it. */
    private suspend fun fanOut(grp: Group, pkt: ByteArray, except: String?): Int {
        var n = 0
        for (m in grp.members) {
            if (m.id == myId || m.id == except) continue
            val s = engine.sessionFor(m.id) ?: continue
            if (engine.peer(m.id)?.knownCode?.let { it >= RadarEngine.GROUPS_SINCE } != true) continue
            if (s.send(A.GTEXT, pkt)) n++
        }
        return n
    }

    suspend fun handle(s: SecureSession, type: Byte, p: ByteArray) {
        val from = engine.peerOf(s)?.id ?: return
        when (type) {
            A.GROUP -> {
                val grp = parseRoster(p) ?: return
                if (!grp.has(myId) || !grp.has(from)) return
                val cur = group(grp.id)
                if (cur != null && cur.version >= grp.version) return
                if (cur != null && cur.creator != grp.creator) return      // only the creator updates a group
                save(grp)
                if (cur == null) engine.messenger.insertInfo("👥 ${grp.nameOf(grp.creator) ?: "Someone"} added you to ${grp.name}", grp.conv)
                // pass the roster on to members that may not have it yet
                grp.members.filter { it.id != myId && it.id != from }.forEach { m -> engine.sessionFor(m.id)?.send(A.GROUP, grp.signed) }
            }
            A.GTEXT -> {
                val r = In(p)
                val gid = r.raw(16).toHex()
                val msgId = r.uuid()
                val origin = r.raw(32)
                val sentAt = r.l()
                val text = r.str(4000).take(Messenger.MAX_TEXT)
                val sig = r.raw(64)
                val grp = group(gid) ?: return
                val originId = origin.toHex()
                if (!grp.has(originId) || !grp.has(from) || originId == myId) return
                val signedPart = "WR-GTEXT".toByteArray() + gid.hexBytes() + Out().uuid(msgId).l(sentAt).build() + text.toByteArray()
                if (!Crypto.ed25519Verify(origin, sig, signedPart)) return
                val id = msgId.toString()
                val e = MessageEntity(
                    id = id, outgoing = false, kind = MsgKind.TEXT, bodyEnc = g.messages.encryptBody(id, text),
                    senderSeq = 0, sentAt = sentAt, localTime = System.currentTimeMillis(), status = MsgStatus.RECEIVED,
                    read = g.uiVisibility.chatVisible && g.uiVisibility.openConv == grp.conv, conv = grp.conv, sender = originId,
                )
                if (!g.messages.insertIgnore(e)) return               // already have it: stop the flood here
                val pkt = packText(gid, msgId, origin, sentAt, text, sign = false, sig = sig)
                remember(id, pkt)
                fanOut(grp, pkt, except = from)
                engine.messenger.onIncoming(e, text, "${grp.nameOf(originId) ?: "Someone"} · ${grp.name}")
            }
        }
    }

    // ---------------------------------------------------------------- catch-up

    /** Recent group packets, re-offered to members who come back (they de-duplicate). */
    private val recent = LinkedHashMap<String, ByteArray>()
    private fun remember(id: String, pkt: ByteArray) {
        synchronized(recent) {
            recent[id] = pkt
            while (recent.size > 60) recent.remove(recent.keys.first())
        }
    }

    /** A contact (running MK19+) connected: give them our groups and recent messages. */
    suspend fun onPeerUp(contactId: String) {
        val s = engine.sessionFor(contactId) ?: return
        val mine = _groups.value.filter { it.has(contactId) }
        if (mine.isEmpty()) return
        mine.forEach { s.send(A.GROUP, it.signed) }
        val ids = mine.map { it.id.hexBytes() }
        val pkts = synchronized(recent) { recent.values.toList() }
        pkts.filter { pk -> ids.any { pk.copyOfRange(0, 16).contentEquals(it) } }.forEach { s.send(A.GTEXT, it) }
        // and messages written while nobody was connected
        for (grp in mine) for (m in g.messages.unacknowledgedIn(grp.conv)) {
            val text = g.messages.textOf(m)
            val pkt = packText(grp.id, UUID.fromString(m.id), g.identity.publicKey, m.sentAt, text, sign = true)
            if (s.send(A.GTEXT, pkt)) g.messages.setStatus(m.id, MsgStatus.DELIVERED)
        }
    }

    /** Leaves a group on this watch (deletes its chat). */
    suspend fun leave(groupId: String) {
        val grp = group(groupId) ?: return
        g.messages.clearConversation(grp.conv)
        _groups.value = _groups.value.filter { it.id != groupId }
        persist()
    }

    fun onContactRemoved(@Suppress("UNUSED_PARAMETER") id: String) = Unit   // groups keep working through other members

    // ---------------------------------------------------------------- storage (Keystore-sealed)

    private fun save(grp: Group) {
        _groups.value = _groups.value.filter { it.id != grp.id } + grp
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        _groups.value.forEach { arr.put(JSONObject().put("r", Base64.encodeToString(it.signed, Base64.NO_WRAP))) }
        val blob = g.vault.encrypt(arr.toString().toByteArray(), "wr-groups")
        prefs.edit().putString("g", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun load(): List<Group> = runCatching {
        val enc = prefs.getString("g", null) ?: return emptyList()
        val plain = g.vault.decrypt(Base64.decode(enc, Base64.NO_WRAP), "wr-groups") ?: return emptyList()
        val arr = JSONArray(String(plain))
        (0 until arr.length()).mapNotNull { parseRoster(Base64.decode(arr.getJSONObject(it).getString("r"), Base64.NO_WRAP)) }
    }.getOrDefault(emptyList())

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
