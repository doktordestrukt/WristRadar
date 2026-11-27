package com.wristradar.core

import android.graphics.Bitmap
import com.wristradar.AppGraph
import com.wristradar.data.db.MessageEntity
import com.wristradar.data.db.MsgKind
import com.wristradar.data.db.MsgStatus
import com.wristradar.protocol.A
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import com.wristradar.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reliable messaging on top of [SecureSession]:
 *  - every message has a UUID and a per-sender sequence number
 *  - receiver stores with INSERT IGNORE on the UUID (duplicate suppression) and always ACKs
 *  - sender keeps PENDING messages, retries while connected and flushes them after reconnects
 */
class Messenger(private val g: AppGraph, private val engine: RadarEngine) {

    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private val images = ImageTransfer(g) { onIncoming(it, if (it.kind == MsgKind.AUDIO) "🎤 Voice message" else "📷 Photo") }
    @Volatile private var imageJobActive = false
    val busy: Boolean get() = imageJobActive

    // ---------------------------------------------------------------- MK19 conversations

    /** "" for the primary contact (all chats from before MK19), "c:<id>" for other contacts. */
    fun convOf(contactId: String): String = if (g.trust.isPrimary(contactId)) "" else "c:$contactId"

    /** The contact a 1:1 conversation is with (null for groups). */
    fun contactOfConv(conv: String): String? = when {
        conv.isEmpty() -> g.trust.partner.value?.id
        conv.startsWith("c:") -> conv.substring(2)
        else -> null
    }

    private fun sessionForConv(conv: String): SecureSession? = contactOfConv(conv)?.let { engine.sessionFor(it) }

    /** Message types a non-primary contact may send (games, updates etc. stay with the primary). */
    private val contactTypes = setOf(
        A.TEXT, A.ACK, A.PROFILE, A.STATUS, A.SEEN, A.REACT, A.EDIT, A.DELETE, A.PIN, A.VERSION,
        A.PQ_ID, A.TYPING, A.UNPAIR, A.NUDGE, A.EFFECT, A.GROUP, A.GTEXT, A.GCALL,
        A.CALL_INVITE, A.CALL_ACCEPT, A.CALL_DECLINE, A.CALL_END, A.CALL_AUDIO, A.CALL_MUTE,
    )

    suspend fun handle(session: SecureSession, type: Byte, p: ByteArray) {
        val peer = engine.peerOf(session)
        val primary = peer?.primary ?: true
        if (!primary) { handleContact(session, peer!!, type, p); return }
        when (type) {
            A.TEXT -> onText(session, p)
            A.ACK -> {
                val id = In(p).uuid().toString()
                val e = g.messages.get(id)
                if (e != null && e.outgoing && e.status != MsgStatus.DELIVERED && e.status != MsgStatus.SEEN) {
                    g.messages.setProgress(id, 100, MsgStatus.DELIVERED)
                }
                lastAttempt.remove(id)
            }
            A.PROFILE -> {
                val r = In(p)
                val name = r.str(200)
                val avatar = r.bytes(60_000)
                val bio = if (r.hasMore()) r.str(600) else null
                // V8: optional banner + profile effect (older apps don't send these).
                val hasExtras = r.hasMore()
                val banner = if (hasExtras) r.bytes(60_000) else null
                val effect = if (r.hasMore()) r.str(24) else null
                withContext(Dispatchers.IO) {
                    if (hasExtras) g.profile.setPartnerExtras(banner?.takeIf { Images.validateJpeg(it) != null }, clearBanner = banner == null, effect = effect)
                    if (bio != null) g.profile.setPartnerBio(bio)
                    g.profile.setPartner(name, avatar?.takeIf { Images.validateJpeg(it) != null })
                    if (avatar == null) g.profile.clearPartnerAvatar()
                }
            }
            A.STATUS -> {
                val r = In(p)
                val battery = r.b().toInt()
                val rssi = r.i()
                if (r.hasMore()) engine.onPartnerPhone((r.b().toInt() and 1) == 1)
                engine.onRemoteStatus(session, if (battery in 0..100) battery else null, if (rssi in -127..0) rssi else null)
            }
            A.IMG_START -> images.onStart(session, p)
            A.IMG_CHUNK -> images.onChunk(session, p)
            A.IMG_STATUS -> images.onStatus(p)
            A.IMG_FAIL -> images.onFail(p)
            A.UNPAIR -> engine.onRemoteUnpair(session)
            A.SEEN -> {
                val r = In(p)
                val n = r.i().coerceIn(0, 200)
                g.messages.markSeen(List(n) { r.uuid().toString() })
            }
            A.REACT -> {
                val r = In(p); val id = r.uuid().toString(); val emoji = r.str(32).take(8)
                if (g.messages.get(id) != null) g.messages.setReactTheirs(id, emoji.ifEmpty { null })
            }
            A.EDIT -> {
                val r = In(p); val id = r.uuid().toString(); val text = r.str(4000).take(MAX_TEXT)
                // Only the author may edit: the partner can only change messages they sent (incoming here).
                val e = g.messages.get(id)
                if (e != null && !e.outgoing && e.kind == MsgKind.TEXT && text.isNotBlank()) g.messages.setText(id, text)
            }
            A.DELETE -> {
                val id = In(p).uuid().toString()
                val e = g.messages.get(id)
                if (e != null && !e.outgoing && e.kind != MsgKind.ALERT) g.messages.delete(id)
            }
            A.PIN -> {
                val r = In(p); val id = r.uuid().toString(); val on = r.b().toInt() == 1
                if (g.messages.get(id) != null) g.messages.setPinned(id, on)
            }
            A.VERSION, A.UPD_OFFER, A.UPD_REQ, A.UPD_CHUNK, A.UPD_ACK -> engine.updater.handle(session, type, p)
            A.SECRET -> onText(session, p, secret = true)
            A.OPENED -> Unit   // secret messages removed in V5
            A.BLAST, A.GAME, A.TAPS, A.TODO, A.MOOD, A.EFFECT -> engine.extras.handle(type, p)
            A.SOS -> Unit   // SOS removed in V6
            A.PQ_ID -> engine.onPeerPqIdentity(session, p)
            A.GROUP, A.GTEXT -> engine.groups.handle(session, type, p)
            A.GCALL -> engine.calls.group.onPacket(p, engine.peerOf(session)?.id ?: return)
            A.NUDGE -> {
                g.notifier.nudge()
                insertInfo("👋 ${g.profile.partnerName.value ?: "Your friend"} nudged you")
                if (!g.uiVisibility.chatVisible) g.notifier.showInfo(g.profile.partnerName.value ?: "Your friend", "👋 Nudge!")
            }
            A.TYPING -> engine.onPeerTyping(session, In(p).b().toInt() == 1)
            A.CALL_INVITE, A.CALL_ACCEPT, A.CALL_DECLINE, A.CALL_END, A.CALL_AUDIO, A.CALL_MUTE -> engine.calls.onSignal(type, p)
            A.SECURITY_NOTICE -> {
                val what = In(p).str(300)
                g.blackbox.partnerNotice(what)
                insertAlert("⚠ Your partner's watch blocked an intercepted message")
            }
            else -> {
                // Sent by a newer WristRadar: tell the user once per kind instead of silently dropping it.
                if (type >= 0x40 && unknownSeen.add(type)) {
                    insertInfo("✨ ${g.profile.partnerName.value ?: "Your friend"} used a feature from a newer WristRadar. Update to see it.")
                }
            }
        }
    }

    private val unknownSeen = java.util.Collections.synchronizedSet(HashSet<Byte>())

    /** Everything a non-primary contact can send. */
    private suspend fun handleContact(session: SecureSession, peer: RadarEngine.PeerLink, type: Byte, p: ByteArray) {
        if (type !in contactTypes) return
        when (type) {
            A.TEXT -> onText(session, p)
            A.ACK -> {
                val id = In(p).uuid().toString()
                val e = g.messages.get(id)
                if (e != null && e.outgoing && e.status != MsgStatus.DELIVERED && e.status != MsgStatus.SEEN) g.messages.setProgress(id, 100, MsgStatus.DELIVERED)
                lastAttempt.remove(id)
            }
            A.PROFILE -> {
                val name = com.wristradar.data.ProfileStore.sanitizeName(In(p).str(200))
                name?.takeIf { it.isNotBlank() }?.let { g.trust.setContactName(peer.id, it) }
            }
            A.STATUS -> {
                val r = In(p); val battery = r.b().toInt(); val rssi = r.i()
                engine.onRemoteStatus(session, if (battery in 0..100) battery else null, if (rssi in -127..0) rssi else null)
            }
            A.SEEN -> { val r = In(p); val n = r.i().coerceIn(0, 200); g.messages.markSeen(List(n) { r.uuid().toString() }) }
            A.REACT -> { val r = In(p); val id = r.uuid().toString(); val emoji = r.str(32).take(8); if (g.messages.get(id) != null) g.messages.setReactTheirs(id, emoji.ifEmpty { null }) }
            A.EDIT -> {
                val r = In(p); val id = r.uuid().toString(); val text = r.str(4000).take(MAX_TEXT)
                val e = g.messages.get(id)
                if (e != null && !e.outgoing && e.kind == MsgKind.TEXT && e.conv == convOf(peer.id) && text.isNotBlank()) g.messages.setText(id, text)
            }
            A.DELETE -> { val id = In(p).uuid().toString(); val e = g.messages.get(id); if (e != null && !e.outgoing && e.conv == convOf(peer.id)) g.messages.delete(id) }
            A.PIN -> { val r = In(p); val id = r.uuid().toString(); val on = r.b().toInt() == 1; if (g.messages.get(id) != null) g.messages.setPinned(id, on) }
            A.VERSION -> runCatching { engine.onPeerVersion(session, In(p).i()) }
            A.PQ_ID -> engine.onPeerPqIdentity(session, p)
            A.TYPING -> engine.onPeerTyping(session, In(p).b().toInt() == 1)
            A.UNPAIR -> engine.onRemoteUnpair(session)
            A.NUDGE -> { g.notifier.nudge(); insertInfo("👋 ${peer.name} nudged you", convOf(peer.id)) }
            A.EFFECT -> engine.extras.handle(type, p)
            A.GROUP, A.GTEXT -> engine.groups.handle(session, type, p)
            A.GCALL -> engine.calls.group.onPacket(p, peer.id)
            else -> engine.calls.onSignal(type, p, peer.id)
        }
    }

    /** Neutral note in the chat (not an alert). */
    suspend fun insertInfo(text: String, conv: String = "") {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        g.messages.upsert(
            MessageEntity(
                id = id, outgoing = false, kind = MsgKind.INFO, bodyEnc = g.messages.encryptBody(id, text),
                senderSeq = 0, sentAt = now, localTime = now, status = MsgStatus.RECEIVED, read = true, conv = conv,
            )
        )
    }

    private var lastNudge = 0L

    /** Buzz the partner's watch (max once every 5 s). */
    fun nudge(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNudge < 5_000) return false
        val s = engine.currentSession() ?: return false
        lastNudge = now
        engine.scope.launch { s.send(A.NUDGE); insertInfo("👋 You nudged ${g.profile.partnerName.value ?: "your friend"}") }
        return true
    }

    private suspend fun onText(session: SecureSession, p: ByteArray, secret: Boolean = false) {
        val r = In(p)
        val id = r.uuid()
        val seq = r.l()
        val sentAt = r.l()
        val text = r.str(4000).take(MAX_TEXT)
        if (secret && r.hasMore()) r.b()   // secret messages were removed: show as normal text
        val replyTo = if (!secret && r.hasMore()) runCatching { r.uuid().toString() }.getOrNull() else null
        val sid = id.toString()
        val peer = engine.peerOf(session)
        val conv = peer?.let { convOf(it.id) } ?: ""
        val entity = MessageEntity(
            id = sid, outgoing = false, kind = MsgKind.TEXT,
            bodyEnc = g.messages.encryptBody(sid, text), senderSeq = seq, sentAt = sentAt,
            localTime = System.currentTimeMillis(), status = MsgStatus.RECEIVED,
            read = g.uiVisibility.chatVisible && g.uiVisibility.openConv == conv, replyTo = replyTo, conv = conv,
        )
        val isNew = g.messages.insertIgnore(entity)
        session.send(A.ACK, Out().uuid(id).build())          // ACK duplicates too
        if (isNew) onIncoming(entity, text, peer?.name)
    }

    suspend fun onIncoming(e: MessageEntity, rawPreview: String, fromName: String? = null) {
        val preview = com.wristradar.ui.components.RichText.plain(rawPreview)
        com.wristradar.glance.Glance.refresh(engine.ctx)
        val title = fromName ?: g.profile.partnerName.value ?: "Partner"
        if (g.uiVisibility.chatVisible && g.uiVisibility.openConv == e.conv) {
            if (e.conv.isEmpty()) sendSeen(g.messages.markAllRead())
            else { val ids = g.messages.markReadIn(e.conv); sendSeenIn(e.conv, ids) }
            g.notifier.tick()
        } else {
            val showText = g.settings.current.previews && !g.lock.locked.value
            if (g.uiVisibility.appVisible) com.wristradar.ui.components.BannerBus.show(title, if (showText) preview else "New message")
            g.notifier.showMessage(title, preview, g.messages.unreadCount())
        }
    }

    // ---------- sending ----------

    suspend fun sendText(raw: String, replyTo: String? = null, conv: String = ""): Boolean {
        engine.lastActivity = System.currentTimeMillis()
        val text = raw.trim().take(MAX_TEXT)
        if (text.isEmpty() || g.trust.partner.value == null) return false
        if (conv.startsWith("g:")) return engine.groups.sendText(conv.substring(2), text)
        val id = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val e = MessageEntity(
            id = id.toString(), outgoing = true, kind = MsgKind.TEXT,
            bodyEnc = g.messages.encryptBody(id.toString(), text), senderSeq = g.trust.nextSequence(),
            sentAt = now, localTime = now, status = MsgStatus.PENDING, replyTo = replyTo, conv = conv,
        )
        g.messages.upsert(e)
        (if (conv.isEmpty()) engine.currentSession() else sessionForConv(conv))?.let { transmitText(it, e, text) }
        return true
    }

    suspend fun sendImage(bitmap: Bitmap): Boolean {
        engine.lastActivity = System.currentTimeMillis()
        if (g.trust.partner.value == null) return false
        val (jpeg, w, h) = withContext(Dispatchers.Default) { Images.prepareForSending(bitmap) }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) { g.messages.saveImage(id, jpeg) }
        val e = MessageEntity(
            id = id, outgoing = true, kind = MsgKind.IMAGE, bodyEnc = null,
            senderSeq = g.trust.nextSequence(), sentAt = now, localTime = now,
            status = MsgStatus.PENDING, width = w, height = h,
        )
        g.messages.upsert(e)
        engine.currentSession()?.let { kickImages(it) }
        return true
    }

    suspend fun sendVoice(voice: ByteArray): Boolean {
        engine.lastActivity = System.currentTimeMillis()
        if (g.trust.partner.value == null) return false
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) { g.messages.saveImage(id, voice) }
        val e = MessageEntity(
            id = id, outgoing = true, kind = MsgKind.AUDIO, bodyEnc = null,
            senderSeq = g.trust.nextSequence(), sentAt = now, localTime = now,
            status = MsgStatus.PENDING, durationMs = com.wristradar.audio.Adpcm.durationMs(voice),
        )
        g.messages.upsert(e)
        engine.currentSession()?.let { kickImages(it) }
        return true
    }

    /** Tells the partner these incoming messages were seen (their ticks turn blue). */
    fun sendSeen(ids: List<String>) {
        if (ids.isEmpty()) return
        val s = engine.currentSession() ?: return
        val out = Out().i(ids.size)
        ids.take(200).forEach { out.uuid(UUID.fromString(it)) }
        engine.scope.launch { s.send(A.SEEN, out.build()) }
    }

    /** Red in-chat notice for a message that was intercepted/tampered with and blocked. */
    suspend fun insertAlert(text: String) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        g.messages.upsert(
            MessageEntity(
                id = id, outgoing = false, kind = MsgKind.ALERT, bodyEnc = g.messages.encryptBody(id, text),
                senderSeq = 0, sentAt = now, localTime = now, status = MsgStatus.RECEIVED, read = true,
            )
        )
    }

    // ---------- reactions, edits, deletes, pins (queued until connected) ----------

    private val pendingActions = java.util.concurrent.ConcurrentLinkedQueue<Pair<Byte, ByteArray>>()

    /** Routes an action about a message to the contact of that message's conversation. */
    private suspend fun sendActionFor(msgId: String, type: Byte, payload: ByteArray) {
        val conv = g.messages.get(msgId)?.conv ?: ""
        if (conv.startsWith("g:")) return            // groups: reactions/edits stay local for now
        if (conv.isEmpty()) { sendAction(type, payload); return }
        sessionForConv(conv)?.let { s -> engine.scope.launch { s.send(type, payload) } }
    }

    /** Seen receipts for a non-primary contact's chat. */
    fun sendSeenIn(conv: String, ids: List<String>) {
        if (ids.isEmpty() || conv.startsWith("g:")) return
        val s = (if (conv.isEmpty()) engine.currentSession() else sessionForConv(conv)) ?: return
        val out = Out().i(ids.size)
        ids.take(200).forEach { out.uuid(UUID.fromString(it)) }
        engine.scope.launch { s.send(A.SEEN, out.build()) }
    }

    private fun sendAction(type: Byte, payload: ByteArray) {
        val s = engine.currentSession()
        if (s == null) { pendingActions.add(type to payload); return }
        engine.scope.launch { if (!s.send(type, payload)) pendingActions.add(type to payload) }
    }

    suspend fun react(id: String, emoji: String?) {
        val e = g.messages.get(id) ?: return
        val new = if (e.reactMine == emoji) null else emoji
        g.messages.setReactMine(id, new)
        sendActionFor(id, A.REACT, Out().uuid(UUID.fromString(id)).str(new ?: "").build())
    }

    suspend fun edit(id: String, raw: String) {
        val e = g.messages.get(id) ?: return
        val text = raw.trim().take(MAX_TEXT)
        if (!e.outgoing || e.kind != MsgKind.TEXT || text.isEmpty()) return
        g.messages.setText(id, text)
        sendActionFor(id, A.EDIT, Out().uuid(UUID.fromString(id)).str(text).build())
    }

    /** Deletes a message here; my own messages are deleted on both watches. */
    suspend fun deleteForBoth(id: String) {
        val e = g.messages.get(id) ?: return
        if (e.outgoing) sendActionFor(id, A.DELETE, Out().uuid(UUID.fromString(id)).build())
        g.messages.delete(id)
    }

    suspend fun togglePin(id: String) {
        val e = g.messages.get(id) ?: return
        g.messages.setPinned(id, !e.pinned)
        sendActionFor(id, A.PIN, Out().uuid(UUID.fromString(id)).b(if (!e.pinned) 1 else 0).build())
    }

    fun sendTyping(typing: Boolean) {
        val s = engine.currentSession() ?: return
        engine.scope.launch { s.send(A.TYPING, byteArrayOf(if (typing) 1 else 0)) }
    }

    suspend fun sendSecurityNotice(s: SecureSession, text: String) = s.send(A.SECURITY_NOTICE, Out().str(text).build())

    suspend fun retry(id: String) {
        val e = g.messages.get(id) ?: return
        if (!e.outgoing || e.status != MsgStatus.FAILED) return
        g.messages.setProgress(id, 0, MsgStatus.PENDING)
        engine.currentSession()?.let { s ->
            if (e.kind == MsgKind.TEXT) transmitText(s, e, g.messages.textOf(e)) else kickImages(s)
        }
    }

    private suspend fun transmitText(s: SecureSession, e: MessageEntity, text: String) {
        lastAttempt[e.id] = System.currentTimeMillis()
        val out = Out().uuid(UUID.fromString(e.id)).l(e.senderSeq).l(e.sentAt).str(text)
        e.replyTo?.let { runCatching { out.uuid(UUID.fromString(it)) } }
        s.send(A.TEXT, out.build())
    }

    /** Sends queued images one at a time in the background. */
    private fun kickImages(s: SecureSession) {
        if (imageJobActive) return
        imageJobActive = true
        engine.scope.launch {
            try {
                while (!s.link.closed) {
                    val next = g.messages.unacknowledged()
                        .firstOrNull { it.kind != MsgKind.TEXT && it.status != MsgStatus.FAILED } ?: break
                    if (!images.send(s, next)) break
                    // wait briefly for the receiver's ACK so we don't loop on the same image
                    var waited = 0
                    while (waited < 10_000 && g.messages.get(next.id)?.status == MsgStatus.TRANSFERRING) {
                        kotlinx.coroutines.delay(250); waited += 250
                    }
                    if (g.messages.get(next.id)?.status == MsgStatus.TRANSFERRING) {
                        g.messages.setStatus(next.id, MsgStatus.PENDING); break
                    }
                }
            } finally {
                imageJobActive = false
            }
        }
    }

    // ---------- session lifecycle ----------

    suspend fun onSessionUp(s: SecureSession) {
        sendSeen(g.messages.recentReadIncomingIds())
        while (true) { val (t, pl) = pendingActions.poll() ?: break; s.send(t, pl) }
        sendProfile(s)
        sendStatus(s, null)
        val pending = g.messages.unacknowledged()
        for (e in pending) if (e.kind == MsgKind.TEXT) transmitText(s, e, g.messages.textOf(e))
        if (pending.any { it.kind != MsgKind.TEXT }) kickImages(s)
    }

    /** A non-primary contact connected: send my name and anything they missed. */
    suspend fun onPeerSessionUp(s: SecureSession) {
        val peer = engine.peerOf(s) ?: return
        sendProfile(s)
        sendStatus(s, null)
        for (e in g.messages.unacknowledgedIn(convOf(peer.id))) if (e.kind == MsgKind.TEXT) transmitText(s, e, g.messages.textOf(e))
    }

    suspend fun sendProfile(s: SecureSession) {
        val avatar = withContext(Dispatchers.Default) { g.profile.myAvatarForSync() }
        val banner = withContext(Dispatchers.Default) { g.profile.myBannerForSync() }
        s.send(A.PROFILE, Out().str(g.profile.myName.value).bytes(avatar).str(g.profile.myBio.value)
            .bytes(banner).str(g.profile.myEffect.value.name).build())
    }

    suspend fun sendStatus(s: SecureSession, rssi: Int?): Boolean =
        s.send(A.STATUS, Out().b((g.battery.percent() ?: -1).toByte()).i(rssi ?: Int.MIN_VALUE).b(if (engine.presence.value.myPhone == true) 1 else 0).build())

    suspend fun sendUnpair(s: SecureSession) = s.send(A.UNPAIR)

    /** Send time of the oldest text the friend hasn't acknowledged yet (null = none). */
    suspend fun oldestUnackedTextAt(conv: String = ""): Long? = g.messages.unacknowledgedIn(conv).filter { it.kind == MsgKind.TEXT }.minOfOrNull { it.localTime }

    /** Called periodically while connected: re-send texts that were not acknowledged. */
    suspend fun retryTick(s: SecureSession) {
        val now = System.currentTimeMillis()
        val peer = engine.peerOf(s)
        if (peer != null && !peer.primary) {
            for (e in g.messages.unacknowledgedIn(convOf(peer.id))) {
                if (e.kind != MsgKind.TEXT) continue
                if (now - (lastAttempt[e.id] ?: 0L) > RETRY_MS) transmitText(s, e, g.messages.textOf(e))
            }
            return
        }
        for (e in g.messages.unacknowledged()) {
            if (e.kind != MsgKind.TEXT) continue
            val last = lastAttempt[e.id] ?: 0L
            if (now - last > RETRY_MS) transmitText(s, e, g.messages.textOf(e))
        }
        if (!imageJobActive && g.messages.unacknowledged().any { it.kind != MsgKind.TEXT && it.status == MsgStatus.PENDING }) {
            kickImages(s)
        }
    }

    companion object {
        const val MAX_TEXT = 1000
        const val RETRY_MS = 12_000L
    }
}
