package com.wristradar.core

import com.wristradar.AppGraph
import com.wristradar.ble.ClientLink
import com.wristradar.crypto.Crypto
import com.wristradar.data.db.MessageEntity
import com.wristradar.data.db.MsgKind
import com.wristradar.data.db.MsgStatus
import com.wristradar.protocol.A
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import com.wristradar.util.Images
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chunked, acknowledged image transfer inside the encrypted session (go-back-N, window 4).
 *
 *  sender   IMG_START(id, seq, sentAt, size, chunk, sha256, w, h)
 *  receiver IMG_STATUS(id, nextNeeded)       <- also the resume point after reconnects
 *  sender   IMG_CHUNK(id, idx, data) ...      receiver acks each with IMG_STATUS
 *  receiver verifies length + SHA-256 + decodes as image -> stores encrypted -> ACK(id)
 *           or IMG_FAIL(id)
 * Partial data is kept only in memory (never written unencrypted to disk).
 */
class ImageTransfer(private val g: AppGraph, private val onIncomingComplete: suspend (MessageEntity) -> Unit) {

    private class Outgoing { val events = Channel<Int>(Channel.CONFLATED) }

    private class Incoming(
        val size: Int, val chunkSize: Int, val sha: ByteArray, val total: Int,
    ) {
        val buf = ByteArrayOutputStream(size)
        var next = 0
        var lastPct = -1
    }

    private val outgoing = ConcurrentHashMap<UUID, Outgoing>()
    private val incoming = HashMap<UUID, Incoming>()
    private val sendMutex = Mutex()
    private val audioIds = HashMap<UUID, Boolean>()

    /** Returns true when the receiver has every chunk. Leaves status PENDING on link loss for retry. */
    suspend fun send(session: SecureSession, e: MessageEntity): Boolean = sendMutex.withLock {
        val id = UUID.fromString(e.id)
        val bytes = g.messages.loadImageBytes(e.id) ?: run {
            g.messages.setStatus(e.id, MsgStatus.FAILED); return@withLock false
        }
        val total = (bytes.size + CHUNK - 1) / CHUNK
        val o = Outgoing()
        outgoing[id] = o
        val client = session.link as? ClientLink
        client?.setHighPriority(true)
        var result = false
        try {
            g.messages.setProgress(e.id, 0, MsgStatus.TRANSFERRING)
            val start = Out().uuid(id).l(e.senderSeq).l(e.sentAt).i(bytes.size).i(CHUNK)
                .raw(Crypto.sha256(bytes)).i(e.width).i(e.height)
                .b(if (e.kind == MsgKind.AUDIO) 1 else 0).i(e.durationMs).build()
            if (!session.send(A.IMG_START, start)) return@withLock false
            var acked = withTimeoutOrNull(10_000) { o.events.receive() } ?: return@withLock false
            if (acked == FAILED) { g.messages.setStatus(e.id, MsgStatus.FAILED); return@withLock false }
            var sent = acked
            var timeouts = 0
            var lastPct = -1
            while (acked < total) {
                while (sent < total && sent - acked < WINDOW) {
                    val from = sent * CHUNK
                    val chunk = bytes.copyOfRange(from, minOf(bytes.size, from + CHUNK))
                    if (!session.send(A.IMG_CHUNK, Out().uuid(id).i(sent).bytes(chunk).build())) return@withLock false
                    sent++
                }
                val ev = withTimeoutOrNull(8_000) { o.events.receive() }
                when {
                    ev == null -> { if (++timeouts > 3) return@withLock false; sent = acked } // go back N
                    ev == FAILED -> { g.messages.setStatus(e.id, MsgStatus.FAILED); return@withLock false }
                    else -> { acked = maxOf(acked, ev); timeouts = 0 }
                }
                val pct = acked * 100 / total
                if (pct / 10 != lastPct / 10) { lastPct = pct; g.messages.setProgress(e.id, pct, MsgStatus.TRANSFERRING) }
            }
            result = true
            true
        } finally {
            outgoing.remove(id)
            client?.setHighPriority(false)
            if (!result) {
                val cur = g.messages.get(e.id)
                if (cur != null && cur.status == MsgStatus.TRANSFERRING) g.messages.setStatus(e.id, MsgStatus.PENDING)
            }
        }
    }

    suspend fun onStart(session: SecureSession, p: ByteArray) {
        val r = In(p)
        val id = r.uuid(); val seq = r.l(); val sentAt = r.l(); val size = r.i(); val chunk = r.i()
        val sha = r.raw(32); val w = r.i(); val h = r.i()
        val isAudio = r.hasMore() && r.b().toInt() == 1
        val dur = if (r.hasMore()) r.i() else 0
        audioIds[id] = isAudio
        val sid = id.toString()
        if (size <= 0 || size > Images.MAX_RECEIVE_BYTES || chunk !in 256..8192) {
            session.send(A.IMG_FAIL, Out().uuid(id).str("rejected").build()); return
        }
        val total = (size + chunk - 1) / chunk
        val existing = g.messages.get(sid)
        if (existing != null && existing.status == MsgStatus.RECEIVED) {   // duplicate after reconnect
            session.send(A.IMG_STATUS, Out().uuid(id).i(total).build())
            session.send(A.ACK, Out().uuid(id).build())
            return
        }
        var inc = incoming[id]
        if (inc == null || inc.size != size || !inc.sha.contentEquals(sha)) {
            if (incoming.size >= 3) incoming.clear()
            inc = Incoming(size, chunk, sha, total)
            incoming[id] = inc
        }
        if (existing == null) {
            g.messages.insertIgnore(
                MessageEntity(
                    id = sid, outgoing = false, kind = if (isAudio) MsgKind.AUDIO else MsgKind.IMAGE, bodyEnc = null, senderSeq = seq,
                    sentAt = sentAt, localTime = System.currentTimeMillis(), status = MsgStatus.TRANSFERRING,
                    progress = 0, width = w, height = h, read = false, durationMs = dur.coerceIn(0, 300_000),
                )
            )
        }
        session.send(A.IMG_STATUS, Out().uuid(id).i(inc.next).build())
    }

    suspend fun onChunk(session: SecureSession, p: ByteArray) {
        val r = In(p)
        val id = r.uuid(); val idx = r.i(); val data = r.bytes(9000) ?: return
        val inc = incoming[id] ?: return
        val expectedLen = if (idx == inc.total - 1) inc.size - idx * inc.chunkSize else inc.chunkSize
        if (idx == inc.next && data.size == expectedLen) {
            inc.buf.write(data)
            inc.next++
        }
        session.send(A.IMG_STATUS, Out().uuid(id).i(inc.next).build())
        val sid = id.toString()
        val pct = inc.next * 100 / inc.total
        if (pct / 10 != inc.lastPct / 10 && inc.next < inc.total) {
            inc.lastPct = pct; g.messages.setProgress(sid, pct, MsgStatus.TRANSFERRING)
        }
        if (inc.next == inc.total) {
            incoming.remove(id)
            val bytes = inc.buf.toByteArray()
            val isAudio = audioIds.remove(id) == true
            val dims = if (isAudio) (if (com.wristradar.audio.Adpcm.unpackVoice(bytes) != null) 0 to 0 else null) else Images.validateJpeg(bytes)
            if (bytes.size != inc.size || !Crypto.ctEquals(Crypto.sha256(bytes), inc.sha) || dims == null) {
                session.send(A.IMG_FAIL, Out().uuid(id).str("validation").build())
                g.messages.setStatus(sid, MsgStatus.FAILED)
                return
            }
            g.messages.saveImage(sid, bytes)
            val e = g.messages.get(sid) ?: return
            val done = if (isAudio) e.copy(status = MsgStatus.RECEIVED, progress = 100, durationMs = com.wristradar.audio.Adpcm.durationMs(bytes))
                else e.copy(status = MsgStatus.RECEIVED, progress = 100, width = dims.first, height = dims.second)
            g.messages.upsert(done)
            session.send(A.ACK, Out().uuid(id).build())
            onIncomingComplete(done)
        }
    }

    fun onStatus(p: ByteArray) {
        val r = In(p)
        val id = r.uuid(); val next = r.i()
        outgoing[id]?.events?.trySend(next)
    }

    fun onFail(p: ByteArray) {
        val id = In(p).uuid()
        outgoing[id]?.events?.trySend(FAILED)
    }

    companion object {
        const val CHUNK = 3000
        const val WINDOW = 4
        private const val FAILED = -2
    }
}
