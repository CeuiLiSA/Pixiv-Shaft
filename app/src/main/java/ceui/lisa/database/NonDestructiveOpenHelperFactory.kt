package ceui.lisa.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import timber.log.Timber
import java.io.File

/**
 * #1261：SQLite 报一次 corrupt，androidx.sqlite 默认的 [SupportSQLiteOpenHelper.Callback.onCorruption]
 * 就把**整个库文件删掉**（Room 不覆写它）。AppDatabase 里装着浏览历史、搜索历史与置顶、屏蔽、
 * 多账号、阅读器笔记/统计、下载记录……哪怕坏的只是下载表里几页，用户看到的也是「像重装了一样」。
 *
 * 这里只换掉 onCorruption，其余回调原样转给 Room：
 *  - 库还开着（某条查询碰到坏页）：不关、不删。异常照常抛给调用方，没坏的表继续可用。
 *  - 库根本打不开：不动它就会每次启动都崩，所以把库文件连同 -wal/-shm/-journal 整组改名
 *    为 `<name>.corrupt*` 挪开，让框架重试时建一个新库；旧数据留在磁盘上，还有机会救回来。
 */
class NonDestructiveOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        delegate.create(
            SupportSQLiteOpenHelper.Configuration(
                configuration.context,
                configuration.name,
                NonDestructiveCallback(configuration.callback),
                configuration.useNoBackupDirectory,
                configuration.allowDataLossOnRecovery,
            )
        )

    private class NonDestructiveCallback(
        private val delegate: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(delegate.version) {

        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            delegate.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            delegate.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            val path = db.path
            Timber.tag(TAG).e("corruption reported on %s, open=%s", path, db.isOpen)
            if (db.isOpen || path.isNullOrBlank() || path.equals(":memory:", ignoreCase = true)) return
            quarantine(path)
        }
    }

    companion object {
        private const val TAG = "NonDestructiveOpenHelper"
        internal const val QUARANTINE_SUFFIX = ".corrupt"
        private val COMPANIONS = listOf("", "-wal", "-shm", "-journal")

        /** 整组改名，保持 SQLite 的命名约定：`x.corrupt` 仍配 `x.corrupt-wal`，挪出去的库还能直接打开。 */
        internal fun quarantine(path: String) {
            COMPANIONS.forEach { suffix ->
                val src = File(path + suffix)
                if (!src.exists()) return@forEach
                val dst = File(path + QUARANTINE_SUFFIX + suffix)
                dst.delete()
                if (!src.renameTo(dst)) {
                    Timber.tag(TAG).e("failed to quarantine %s", src)
                }
            }
        }
    }
}
