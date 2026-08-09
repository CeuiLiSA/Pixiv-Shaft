package ceui.pixiv.actionqueue.internal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本模块私有的库，文件名 `pixiv_action_queue.db`。
 *
 * 刻意**不**并进 app 的主库：主库已经是 v41、19 条手写 migration、24 张表，
 * 往里加一张表就要给所有既有表担一次迁移风险。独立库升级互不影响。
 *
 * 同样刻意**不**用 `fallbackToDestructiveMigration()`：这里躺的是用户点过但还没生效的
 * 收藏和关注，销毁式迁移会把它们静默吃掉。加列必须手写 Migration，
 * 所以 `exportSchema = true` 且 schemas/ 要跟着提交。
 */
@Database(
    entities = [ActionEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class ActionQueueDatabase : RoomDatabase() {

    abstract fun actionDao(): ActionDao

    companion object {

        private const val DB_NAME = "pixiv_action_queue.db"

        @Volatile
        private var instance: ActionQueueDatabase? = null

        fun get(context: Context): ActionQueueDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): ActionQueueDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ActionQueueDatabase::class.java,
                DB_NAME,
            )
                // 不开 allowMainThreadQueries：本库所有访问都在协程里，
                // 开了只会把「不小心在主线程读库」这类问题掩盖掉。
                .build()
    }
}
