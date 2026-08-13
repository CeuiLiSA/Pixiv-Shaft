package ceui.pixiv.actionqueue.internal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * 本模块私有的库；默认文件名 `pixiv_action_queue.db`，也支持按队列用途使用独立库名。
 *
 * 刻意**不**并进 app 的主库：主库已经是 v41、19 条手写 migration、24 张表，
 * 往里加一张表就要给所有既有表担一次迁移风险。独立库升级互不影响。
 *
 * 同样刻意**不**用 `fallbackToDestructiveMigration()`：这里躺的是用户点过但还没生效的
 * 收藏和关注，销毁式迁移会把它们静默吃掉。加列必须手写 Migration，
 * 所以 `exportSchema = true` 且 schemas/ 要跟着提交。
 */
@Database(
    entities = [ActionEntity::class, QueueMetaEntity::class],
    version = 2,
    exportSchema = true,
)
internal abstract class ActionQueueDatabase : RoomDatabase() {

    abstract fun actionDao(): ActionDao

    companion object {

        /**
         * v1 → v2：行加 `owner`（归属账号），另起 `queue_meta` 存整队冷却截止时刻。
         *
         * v1 的存量行一律删掉，不是保留。它们没有归属信息，留着就只有两种结局：要么按
         * 「谁登录谁执行」处理，那正是这次要修的跨账号重放；要么塞一个猜的 owner，
         * 猜错同样会发到错的账号上。代价是极少量还没发出去的点击丢失 —— v1 只存在于
         * 未发布的开发版里，且用户在界面上看到的乐观状态本来就会在下次刷新时被服务端纠正。
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM `queued_action`")
                db.execSQL(
                    "ALTER TABLE `queued_action` ADD COLUMN `owner` TEXT NOT NULL DEFAULT ''"
                )
                // 索引跟着换掉 owner 维度，Room 会在启动时逐字校验索引定义。
                db.execSQL("DROP INDEX IF EXISTS `index_queued_action_status_notBefore_id`")
                db.execSQL("DROP INDEX IF EXISTS `index_queued_action_dedupeKey_status`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_queued_action_status_owner_notBefore_id` " +
                        "ON `queued_action` (`status`, `owner`, `notBefore`, `id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_queued_action_dedupeKey_owner_status` " +
                        "ON `queued_action` (`dedupeKey`, `owner`, `status`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queue_meta` (" +
                        "`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        private val instances = ConcurrentHashMap<String, ActionQueueDatabase>()

        fun get(context: Context, databaseName: String): ActionQueueDatabase {
            require(databaseName.matches(Regex("^[A-Za-z0-9_.-]+\\.db$"))) {
                "invalid action queue database name"
            }
            return instances[databaseName] ?: synchronized(this) {
                instances[databaseName] ?: build(context, databaseName).also {
                    instances[databaseName] = it
                }
            }
        }

        private fun build(context: Context, databaseName: String): ActionQueueDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ActionQueueDatabase::class.java,
                databaseName,
            )
                .addMigrations(MIGRATION_1_2)
                // 不开 allowMainThreadQueries：本库所有访问都在协程里，
                // 开了只会把「不小心在主线程读库」这类问题掩盖掉。
                .build()
    }
}
