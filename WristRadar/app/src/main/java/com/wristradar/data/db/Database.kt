package com.wristradar.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object MsgKind { const val TEXT = 0; const val IMAGE = 1; const val AUDIO = 2; const val ALERT = 3; const val INFO = 4 }

object MsgStatus {
    const val PENDING = 0      // queued, not yet acknowledged
    const val DELIVERED = 1    // partner acknowledged
    const val RECEIVED = 2     // incoming, complete
    const val TRANSFERRING = 3 // image in flight (either direction)
    const val FAILED = 4
    const val SEEN = 5         // partner opened the chat and saw it (blue ticks)
}

/**
 * Message bodies are stored Keystore-encrypted (bodyEnc). Image payloads are separate
 * encrypted files referenced by message id. Metadata (times, status) is stored in clear.
 */
@Entity(tableName = "messages", indices = [Index("localTime")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val outgoing: Boolean,
    val kind: Int,
    val bodyEnc: ByteArray?,
    val senderSeq: Long,
    val sentAt: Long,
    val localTime: Long,
    val status: Int,
    val progress: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val read: Boolean = true,
    @ColumnInfo(defaultValue = "0") val durationMs: Int = 0,
    /** My reaction emoji on this message. */
    val reactMine: String? = null,
    /** Partner's reaction emoji on this message. */
    val reactTheirs: String? = null,
    /** Id of the message this one replies to. */
    val replyTo: String? = null,
    @ColumnInfo(defaultValue = "0") val edited: Boolean = false,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY localTime ASC, senderSeq ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE outgoing = 0 AND read = 0")
    fun observeUnread(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE outgoing = 0 AND read = 0")
    suspend fun unreadCount(): Int

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(m: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: Int)

    @Query("UPDATE messages SET progress = :progress, status = :status WHERE id = :id")
    suspend fun setProgress(id: String, progress: Int, status: Int)

    @Query("SELECT * FROM messages WHERE outgoing = 1 AND status IN (0, 3) ORDER BY localTime ASC")
    suspend fun unacknowledgedOutgoing(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE outgoing = 0 AND read = 0 ORDER BY localTime DESC LIMIT 5")
    suspend fun latestUnread(): List<MessageEntity>

    @Query("SELECT id FROM messages WHERE outgoing = 0 AND read = 0 AND kind < 3")
    suspend fun unreadIncomingIds(): List<String>

    @Query("SELECT id FROM messages WHERE outgoing = 0 AND read = 1 AND kind < 3 ORDER BY localTime DESC LIMIT 50")
    suspend fun recentReadIncomingIds(): List<String>

    @Query("UPDATE messages SET status = 5 WHERE outgoing = 1 AND id IN (:ids) AND status IN (0, 1)")
    suspend fun markSeen(ids: List<String>)

    @Query("UPDATE messages SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE messages SET reactMine = :emoji WHERE id = :id")
    suspend fun setReactMine(id: String, emoji: String?)

    @Query("UPDATE messages SET reactTheirs = :emoji WHERE id = :id")
    suspend fun setReactTheirs(id: String, emoji: String?)

    @Query("UPDATE messages SET bodyEnc = :body, edited = 1 WHERE id = :id")
    suspend fun setBody(id: String, body: ByteArray)

    @Query("UPDATE messages SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
}

/** Local, offline security log for the Blackbox detector. Never contains message content or keys. */
@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val type: String,
    val detail: String,
    val device: String,
    val deviceId: String?,
)

@Dao
interface SecurityEventDao {
    @Query("SELECT * FROM security_events ORDER BY time DESC LIMIT 200")
    fun observe(): Flow<List<SecurityEventEntity>>

    @Insert
    suspend fun insert(e: SecurityEventEntity)

    @Query("DELETE FROM security_events WHERE id NOT IN (SELECT id FROM security_events ORDER BY time DESC LIMIT 500)")
    suspend fun trim()

    @Query("DELETE FROM security_events")
    suspend fun clear()
}

@Database(entities = [MessageEntity::class, SecurityEventEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun securityEvents(): SecurityEventDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "wristradar.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /** V2: reactions, replies, edits and pins. Adds columns only - existing chats are kept. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reactMine TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reactTheirs TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyTo TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `security_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT NOT NULL, `device` TEXT NOT NULL, `deviceId` TEXT)"
                )
            }
        }
    }
}
