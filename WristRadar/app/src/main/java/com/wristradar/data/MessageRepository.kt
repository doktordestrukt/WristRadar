package com.wristradar.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.wristradar.crypto.KeystoreVault
import com.wristradar.data.db.MessageDao
import com.wristradar.data.db.MessageEntity
import com.wristradar.data.db.MsgKind
import com.wristradar.data.db.MsgStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class ChatMessage(
    val id: String,
    val outgoing: Boolean,
    val isImage: Boolean,
    val text: String,
    val sentAt: Long,
    val localTime: Long,
    val status: Int,
    val progress: Int,
    val width: Int,
    val height: Int,
    val read: Boolean,
    val isAudio: Boolean = false,
    val isAlert: Boolean = false,
    val durationMs: Int = 0,
    val reactMine: String? = null,
    val reactTheirs: String? = null,
    val replyTo: String? = null,
    val edited: Boolean = false,
    val pinned: Boolean = false,
    /** Neutral system note (nudges, "update to see this"). */
    val isInfo: Boolean = false,
    val secret: Int = 0,
)

class MessageRepository(
    context: Context,
    private val dao: MessageDao,
    private val vault: KeystoreVault,
) {
    private val imageDir = File(context.filesDir, "images").apply { mkdirs() }
    private val partialDir = File(context.cacheDir, "partial").apply { mkdirs() }
    private val bitmapCache = LruCache<String, Bitmap>(12)

    /** Called when Keystore authentication of stored data fails (possible tampering). */
    @Volatile var onIntegrityFailure: (() -> Unit)? = null

    private fun decryptBody(id: String, blob: ByteArray): String =
        vault.decrypt(blob, "msg:$id")?.toString(Charsets.UTF_8) ?: run { onIntegrityFailure?.invoke(); "[unreadable]" }

    val messages: Flow<List<ChatMessage>> = dao.observeAll().map { list -> list.map { it.toChat() } }
    val unread: Flow<Int> = dao.observeUnread()

    private fun MessageEntity.toChat() = ChatMessage(
        id, outgoing, kind == MsgKind.IMAGE || kind == MsgKind.AUDIO,
        bodyEnc?.let { decryptBody(id, it) } ?: "",
        sentAt, localTime, status, progress, width, height, read,
        kind == MsgKind.AUDIO, kind == MsgKind.ALERT, durationMs,
        reactMine, reactTheirs, replyTo, edited, pinned, kind == MsgKind.INFO, secret,
    )

    fun encryptBody(id: String, text: String): ByteArray = vault.encrypt(text.toByteArray(), "msg:$id")

    suspend fun get(id: String) = dao.get(id)
    suspend fun insertIgnore(m: MessageEntity) = dao.insertIgnore(m) != -1L
    suspend fun upsert(m: MessageEntity) = dao.upsert(m)
    suspend fun setStatus(id: String, status: Int) = dao.setStatus(id, status)
    suspend fun setProgress(id: String, p: Int, status: Int) = dao.setProgress(id, p, status)
    suspend fun unacknowledged() = dao.unacknowledgedOutgoing()
    /** Marks everything read and returns the ids that were unread (to send "seen" receipts). */
    suspend fun markAllRead(): List<String> { val ids = dao.unreadIncomingIds(); dao.markAllRead(); return ids }
    suspend fun recentReadIncomingIds() = dao.recentReadIncomingIds()
    suspend fun markSeen(ids: List<String>) { if (ids.isNotEmpty()) dao.markSeen(ids) }
    suspend fun unreadCount() = dao.unreadCount()
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.delete(id); imageFile(id).delete(); bitmapCache.remove(id) }
    suspend fun setReactMine(id: String, e: String?) = dao.setReactMine(id, e)
    suspend fun setReactTheirs(id: String, e: String?) = dao.setReactTheirs(id, e)
    suspend fun setText(id: String, text: String) = dao.setBody(id, encryptBody(id, text))
    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)
    suspend fun latestUnread(): List<ChatMessage> = dao.latestUnread().map { it.toChat() }

    fun textOf(e: MessageEntity): String =
        e.bodyEnc?.let { decryptBody(e.id, it) } ?: ""

    // ---- images: complete files are encrypted at rest ----
    private fun imageFile(id: String) = File(imageDir, "$id.bin")

    fun saveImage(id: String, jpeg: ByteArray) {
        imageFile(id).writeBytes(vault.encrypt(jpeg, "img:$id"))
    }

    fun loadImageBytes(id: String): ByteArray? {
        val f = imageFile(id)
        return if (f.exists()) vault.decrypt(f.readBytes(), "img:$id") else null
    }

    suspend fun loadBitmap(id: String): Bitmap? = withContext(Dispatchers.IO) {
        bitmapCache.get(id) ?: loadImageBytes(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?.also { bitmapCache.put(id, it) }
    }

    /** Partial incoming transfers (ciphertext of the BLE session is already removed; kept in app cache). */
    fun partialFile(id: String) = File(partialDir, "$id.part")

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        imageDir.listFiles()?.forEach { it.delete() }
        partialDir.listFiles()?.forEach { it.delete() }
        bitmapCache.evictAll()
    }

    companion object {
        fun newIncomingStatus(kind: Int) = if (kind == MsgKind.IMAGE) MsgStatus.TRANSFERRING else MsgStatus.RECEIVED
    }
}
