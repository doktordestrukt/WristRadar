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
import kotlinx.coroutines.flow.flowOn
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
    val conv: String = "",
    /** Groups: contact id of the author. */
    val sender: String? = null,
)

class MessageRepository(
    context: Context,
    private val dao: MessageDao,
    private val vault: KeystoreVault,
) {
    private val imageDir = File(context.filesDir, "images").apply { mkdirs() }
    private val partialDir = File(context.cacheDir, "partial").apply { mkdirs() }
    private val bitmapCache = LruCache<String, Bitmap>(12)

    /**
     * Message bodies used to be sealed one by one with the Keystore key. Every Keystore call goes
     * into the secure hardware and costs several milliseconds, and the whole chat was re-decrypted
     * on every change - that froze the watch. Now one random 256-bit "message key" encrypts bodies
     * in software (AES-256-GCM), and only that key is sealed with the Keystore. Old bodies still
     * open (and are converted in the background); decrypted text is cached by id + blob.
     */
    private val prefs = context.getSharedPreferences("wr_msgkey", Context.MODE_PRIVATE)
    private val msgKey: ByteArray by lazy {
        prefs.getString("k", null)?.let { vault.decrypt(android.util.Base64.decode(it, android.util.Base64.NO_WRAP), "wr-msg-key") }
            ?: com.wristradar.crypto.Crypto.random(32).also { k ->
                prefs.edit().putString("k", android.util.Base64.encodeToString(vault.encrypt(k, "wr-msg-key"), android.util.Base64.NO_WRAP)).commit()
            }
    }
    private val textCache = LruCache<String, String>(3000)

    private fun isEnvelope(b: ByteArray) = b.size > 2 + 12 + 16 && b[0] == MAGIC0 && b[1] == MAGIC1

    /** Called when Keystore authentication of stored data fails (possible tampering). */
    @Volatile var onIntegrityFailure: (() -> Unit)? = null

    private fun decryptBody(id: String, blob: ByteArray): String {
        val ck = id + ":" + blob.contentHashCode()
        textCache.get(ck)?.let { return it }
        val plain = (if (isEnvelope(blob)) openEnvelope(id, blob) else null)
            ?: vault.decrypt(blob, "msg:$id")
        val text = plain?.toString(Charsets.UTF_8) ?: run { onIntegrityFailure?.invoke(); "[unreadable]" }
        if (plain != null) textCache.put(ck, text)
        return text
    }

    private fun openEnvelope(id: String, blob: ByteArray): ByteArray? =
        com.wristradar.crypto.Crypto.gcmOpen(msgKey, blob.copyOfRange(2, 14), blob.copyOfRange(14, blob.size), "msg:$id".toByteArray())

    /** Decrypting happens on a background thread, never on the one that draws the screen. */
    val messages: Flow<List<ChatMessage>> = dao.observeAll().map { list -> list.map { it.toChat() } }.flowOn(Dispatchers.Default)

    // ---- MK19 conversations ("" = primary contact, "c:<id>" = other contacts, "g:<id>" = groups)
    fun messagesIn(conv: String): Flow<List<ChatMessage>> =
        if (conv.isEmpty()) messages else dao.observeConv(conv).map { list -> list.map { it.toChat() } }.flowOn(Dispatchers.Default)
    fun unreadIn(conv: String): Flow<Int> = if (conv.isEmpty()) unread else dao.observeUnreadConv(conv)
    fun lastIn(conv: String): Flow<ChatMessage?> = dao.observeLastConv(conv).map { it?.toChat() }.flowOn(Dispatchers.Default)
    suspend fun markReadIn(conv: String): List<String> =
        if (conv.isEmpty()) markAllRead() else { val ids = dao.unreadIncomingIdsConv(conv); dao.markReadConv(conv); ids }
    suspend fun unacknowledgedIn(conv: String) = if (conv.isEmpty()) dao.unacknowledgedOutgoing() else dao.unacknowledgedConv(conv)
    suspend fun recentIn(conv: String, n: Int) = dao.recentConv(conv, n)
    suspend fun clearConversation(conv: String) = withContext(Dispatchers.IO) { dao.deleteConv(conv) }
    suspend fun renameConversation(from: String, to: String) = withContext(Dispatchers.IO) { dao.renameConv(from, to) }
    val unread: Flow<Int> = dao.observeUnread()

    private fun MessageEntity.toChat() = ChatMessage(
        id, outgoing, kind == MsgKind.IMAGE || kind == MsgKind.AUDIO,
        bodyEnc?.let { decryptBody(id, it) } ?: "",
        sentAt, localTime, status, progress, width, height, read,
        kind == MsgKind.AUDIO, kind == MsgKind.ALERT, durationMs,
        reactMine, reactTheirs, replyTo, edited, pinned, kind == MsgKind.INFO, secret, conv, sender,
    )

    fun encryptBody(id: String, text: String): ByteArray {
        val iv = com.wristradar.crypto.Crypto.random(12)
        return byteArrayOf(MAGIC0, MAGIC1) + iv + com.wristradar.crypto.Crypto.gcmSeal(msgKey, iv, text.toByteArray(), "msg:$id".toByteArray())
    }

    /** One-time background conversion of old Keystore-sealed bodies to the fast format. */
    suspend fun migrateBodies() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean("migrated", false)) return@withContext
        for (e in dao.allWithBody()) {
            val b = e.bodyEnc ?: continue
            if (isEnvelope(b) && openEnvelope(e.id, b) != null) continue
            val plain = vault.decrypt(b, "msg:${e.id}") ?: continue
            dao.replaceBody(e.id, encryptBody(e.id, plain.toString(Charsets.UTF_8)))
        }
        prefs.edit().putBoolean("migrated", true).apply()
    }

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
    suspend fun latestUnread(): List<ChatMessage> = withContext(Dispatchers.Default) { dao.latestUnread().map { it.toChat() } }

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
        private const val MAGIC0: Byte = 0x57   // 'W'
        private const val MAGIC1: Byte = 0x45   // 'E'  (envelope v1)
        fun newIncomingStatus(kind: Int) = if (kind == MsgKind.IMAGE) MsgStatus.TRANSFERRING else MsgStatus.RECEIVED
    }
}
