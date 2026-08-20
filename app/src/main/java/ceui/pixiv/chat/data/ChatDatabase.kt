package ceui.pixiv.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Dedicated Room database for chat messages, separate from [AppDatabase].
 *
 * ## Why a separate database
 *
 * Same rationale as `AnalyticsDatabase`:
 *
 *  1. **Independent migration.** Chat schema will iterate fast (read
 *     receipts, reactions, attachments…). Keeping it in its own file
 *     means `fallbackToDestructiveMigration` only drops the message
 *     cache, not user data.
 *  2. **Independent lifecycle.** Clearing the chat cache (e.g. on
 *     logout) is a single `chatDb.clearAllTables()` — no risk of
 *     touching user or analytics tables.
 *  3. **WAL contention.** Chat inserts are high-frequency (WS messages
 *     + full-page upserts). A separate WAL file keeps the write lock
 *     from blocking the main database's reads.
 */
@Database(
    entities = [ChatMessageEntity::class],
    // v2 (2026-05-14): schema migration for uid-routing protocol (doc
    // §9.2). PK changed from Long messageId → String localKey, threadId
    // dropped in favour of String room, plus serverId/clientMsgId/displayName/state
    // columns added. fallbackToDestructiveMigration drops the old table on
    // the next open — chat had no real users on the old protocol so the
    // wipe is fine.
    // v3 (2026-08-20): reply-to-message — four nullable columns
    // (reply_to_uid / reply_to_cmid / reply_to_display_name / reply_to_text)
    // added via MIGRATION_2_3 (ALTER TABLE ADD COLUMN), preserving the local
    // cache and any optimistic Sending/Failed rows across the upgrade.
    version = 3,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "chat.db",
                )
                    .addMigrations(MIGRATION_2_3)
                    // Safety net for any path MIGRATION_* doesn't cover (e.g. a
                    // downgrade); chat is a cache, so dropping it is acceptable.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }

        /** Additive reply-to columns; all nullable so existing rows need no backfill. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reply_to_uid INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reply_to_cmid TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reply_to_display_name TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reply_to_text TEXT")
            }
        }
    }
}
