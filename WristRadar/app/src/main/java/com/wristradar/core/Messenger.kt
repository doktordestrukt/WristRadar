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
                if (e != null && e.outgoing && e.status != MsgStatus.DELIVERED) {
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
            A.TYPING -> engine.onPartnerTyping(In(p).b().toInt() == 1)
            A.CALL_INVITE, A.CALL_ACCEPT, A.CALL_DECLINE, A.CALL_END, A.CALL_AUDIO -> engine.calls.onSignal(type, p)
            A.SECURITY_NOTICE -> g.blackbox.partnerNotice(In(p).str(300))
        }
    }

    private suspend fun onText(session: SecureSession, p: ByteArray) {
        val r = In(p)
        val id = r.uuid()
        val seq = r.l()
        val sentAt = r.l()
        val text = r.str(4000).take(MAX_TEXT)
        val sid = id.toString()
        val entity = MessageEntity(
            id = sid, outgoing = false, kind = MsgKind.TEXT,
            bodyEnc = g.messages.encryptBody(sid, text), senderSeq = seq, sentAt = sentAt,
            localTime = System.currentTimeMillis(), status = MsgStatus.RECEIVED,
            read = g.uiVisibility.chatVisible,
        )
        val isNew = g.messages.insertIgnore(entity)
        session.send(A.ACK, Out().uuid(id).build())          // ACK duplicates too
        if (isNew) onIncoming(entity, text)
    }

    private suspend fun onIncoming(e: MessageEntity, preview: String) {
        if (g.uiVisibility.chatVisible) {
            g.messages.markAllRead()
            g.notifier.tick()
        } else {
            if (g.uiVisibility.appVisible) com.wristradar.ui.components.BannerBus.show(g.profile.partnerName.value ?: "Partner", if (g.settings.current.previews) preview else "New message")
            g.notifier.showMessage(g.profile.partnerName.value ?: "Partner", preview, g.messages.unreadCount())
        }
    }

    // ---------- sending ----------

    suspend fun sendText(raw: String): Boolean {
        engine.lastActivity = System.currentTimeMillis()
        val text = raw.trim().take(MAX_TEXT)
        if (text.isEmpty() || g.trust.partner.value == null) return false
        val id = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val e = MessageEntity(
            id = id.toString(), outgoing = true, kind = MsgKind.TEXT,
            bodyEnc = g.messages.encryptBody(id.toString(), text), senderSeq = g.trust.nextSequence(),
            sentAt = now, localTime = now, status = MsgStatus.PENDING,
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
        s.send(A.TEXT, Out().uuid(UUID.fromString(e.id)).l(e.senderSeq).l(e.sentAt).str(text).build())
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
