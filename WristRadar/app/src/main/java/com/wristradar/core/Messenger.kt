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

    suspend fun handle(session: SecureSession, type: Byte, p: ByteArray) {
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
                withContext(Dispatchers.IO) {
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
                engine.onRemoteStatus(if (battery in 0..100) battery else null, if (rssi in -127..0) rssi else null)
            }
            A.IMG_START -> images.onStart(session, p)
            A.IMG_CHUNK -> images.onChunk(session, p)
            A.IMG_STATUS -> images.onStatus(p)
            A.IMG_FAIL -> images.onFail(p)
            A.UNPAIR -> engine.onRemoteUnpair()
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
            A.BLAST, A.GAME, A.TAPS, A.TODO, A.MOOD -> engine.extras.handle(type, p)
            A.SOS -> Unit   // SOS removed in V6
            A.NUDGE -> {
                g.notifier.nudge()
                insertInfo("👋 ${g.profile.partnerName.value ?: "Your friend"} nudged you")
                if (!g.uiVisibility.chatVisible) g.notifier.showInfo(g.profile.partnerName.value ?: "Your friend", "👋 Nudge!")
            }
            A.TYPING -> engine.onPartnerTyping(In(p).b().toInt() == 1)
            A.CALL_INVITE, A.CALL_ACCEPT, A.CALL_DECLINE, A.CALL_END, A.CALL_AUDIO -> engine.calls.onSignal(type, p)
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

    /** Neutral note in the chat (not an alert). */
    suspend fun insertInfo(text: String) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        g.messages.upsert(
            MessageEntity(
                id = id, outgoing = false, kind = MsgKind.INFO, bodyEnc = g.messages.encryptBody(id, text),
                senderSeq = 0, sentAt = now, localTime = now, status = MsgStatus.RECEIVED, read = true,
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
        val entity = MessageEntity(
            id = sid, outgoing = false, kind = MsgKind.TEXT,
            bodyEnc = g.messages.encryptBody(sid, text), senderSeq = seq, sentAt = sentAt,
            localTime = System.currentTimeMillis(), status = MsgStatus.RECEIVED,
            read = g.uiVisibility.chatVisible, replyTo = replyTo,
        )
        val isNew = g.messages.insertIgnore(entity)
        session.send(A.ACK, Out().uuid(id).build())          // ACK duplicates too
        if (isNew) onIncoming(entity, text)
    }

    private suspend fun onIncoming(e: MessageEntity, preview: String) {
        com.wristradar.glance.Glance.refresh(engine.ctx)
        if (g.uiVisibility.chatVisible) {
            sendSeen(g.messages.markAllRead())
            g.notifier.tick()
        } else {
            if (g.uiVisibility.appVisible) com.wristradar.ui.components.BannerBus.show(g.profile.partnerName.value ?: "Partner", if (g.settings.current.previews) preview else "New message")
            g.notifier.showMessage(g.profile.partnerName.value ?: "Partner", preview, g.messages.unreadCount())
        }
    }

    // ---------- sending ----------

    suspend fun sendText(raw: String, replyTo: String? = null): Boolean {
        engine.lastActivity = System.currentTimeMillis()
        val text = raw.trim().take(MAX_TEXT)
        if (text.isEmpty() || g.trust.partner.value == null) return false
        val id = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val e = MessageEntity(
            id = id.toString(), outgoing = true, kind = MsgKind.TEXT,
            bodyEnc = g.messages.encryptBody(id.toString(), text), senderSeq = g.trust.nextSequence(),
            sentAt = now, localTime = now, status = MsgStatus.PENDING, replyTo = replyTo,
        )
        g.messages.upsert(e)
        engine.currentSession()?.let { transmitText(it, e, text) }
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

    private fun sendAction(type: Byte, payload: ByteArray) {
        val s = engine.currentSession()
        if (s == null) { pendingActions.add(type to payload); return }
        engine.scope.launch { if (!s.send(type, payload)) pendingActions.add(type to payload) }
    }

    suspend fun react(id: String, emoji: String?) {
        val e = g.messages.get(id) ?: return
        val new = if (e.reactMine == emoji) null else emoji
        g.messages.setReactMine(id, new)
        sendAction(A.REACT, Out().uuid(UUID.fromString(id)).str(new ?: "").build())
    }

    suspend fun edit(id: String, raw: String) {
        val e = g.messages.get(id) ?: return
        val text = raw.trim().take(MAX_TEXT)
        if (!e.outgoing || e.kind != MsgKind.TEXT || text.isEmpty()) return
        g.messages.setText(id, text)
        sendAction(A.EDIT, Out().uuid(UUID.fromString(id)).str(text).build())
    }

    /** Deletes a message here; my own messages are deleted on both watches. */
    suspend fun deleteForBoth(id: String) {
        val e = g.messages.get(id) ?: return
        g.messages.delete(id)
        if (e.outgoing) sendAction(A.DELETE, Out().uuid(UUID.fromString(id)).build())
    }

    suspend fun togglePin(id: String) {
        val e = g.messages.get(id) ?: return
        g.messages.setPinned(id, !e.pinned)
        sendAction(A.PIN, Out().uuid(UUID.fromString(id)).b(if (!e.pinned) 1 else 0).build())
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

    suspend fun sendProfile(s: SecureSession) {
        val avatar = withContext(Dispatchers.Default) { g.profile.myAvatarForSync() }
        s.send(A.PROFILE, Out().str(g.profile.myName.value).bytes(avatar).str(g.profile.myBio.value).build())
    }

    suspend fun sendStatus(s: SecureSession, rssi: Int?): Boolean =
        s.send(A.STATUS, Out().b((g.battery.percent() ?: -1).toByte()).i(rssi ?: Int.MIN_VALUE).b(if (engine.presence.value.myPhone == true) 1 else 0).build())

    suspend fun sendUnpair(s: SecureSession) = s.send(A.UNPAIR)

    /** Called periodically while connected: re-send texts that were not acknowledged. */
    suspend fun retryTick(s: SecureSession) {
        val now = System.currentTimeMillis()
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
