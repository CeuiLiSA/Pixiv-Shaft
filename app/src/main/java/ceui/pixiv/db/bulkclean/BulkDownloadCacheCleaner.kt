package ceui.pixiv.db.bulkclean

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.annotation.WorkerThread
import androidx.sqlite.db.SimpleSQLiteQuery
import ceui.lisa.core.Manager
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.DbCallTracker
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.download.model.DownloadRecordSlim
import ceui.pixiv.services.appServices
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 管理和清理下载相关的持久化数据。
 *
 * 提供两种管理策略：
 * 1. 深度瘦身（推荐）：在完整保留全部历史下载记录与列表视图的前提下，对记录中的冗余字段进行轻量化压缩
 *    （如精简文案、压缩多页作品路径）、清空已完成队列的残留载荷以及废弃列，并通过 VACUUM 释放底层磁盘物理空间。
 * 2. 清空全部：将下载记录与队列全部清空，恢复初始状态。
 *
 * 两者均绝不触碰本地已保存的真实图片或小说文件。
 */
object BulkDownloadCacheCleaner {

    private const val TAG = "BulkDlCacheCleaner"

    /** 单飞闸：清理或瘦身是全库级重 IO 操作，禁止并发叠跑。 */
    private val busy = AtomicBoolean(false)

    data class StorageStats(
        val downloadRecordsBytes: Long = 0L,
        val downloadRecordsCount: Int = 0,
        val queuePayloadBytes: Long = 0L,
        val queueSuccessCount: Int = 0,
        val stagingBytes: Long = 0L,
        val totalBytes: Long = 0L
    )

    data class SlimResult(
        val rowsProcessed: Int = 0,
        val rowsSlimmed: Int = 0,
        val queuePayloadsCleared: Int = 0,
        val bytesSaved: Long = 0L,
        /** 各步骤是否全部成功。没有真正执行（单飞闸挡回）时也是 false。 */
        val complete: Boolean = false,
    )

    /**
     * 批量队列里还有多少条没结束的行（status != SUCCESS）。
     *
     * 给设置页那道"队列不为空就别清"的闸用 —— 清理会打断下载，而下载中的写入又正好是
     * 大事务/checkpoint 最容易撞上的东西。
     */
    @JvmStatic
    @WorkerThread
    @JvmOverloads
    fun activeQueueCount(
        context: Context,
        cancellationSignal: CancellationSignal? = null,
    ): Int {
        cancellationSignal?.throwIfCanceled()
        return runCatching {
            val db = AppDatabase.getAppDatabase(context)
            cancellationSignal?.throwIfCanceled()
            val raw = db.openHelper.writableDatabase
            cancellationSignal?.throwIfCanceled()
            raw.query(
                SimpleSQLiteQuery("SELECT COUNT(*) FROM download_queue WHERE status != '${QueueStatus.SUCCESS}'"),
                cancellationSignal
            ).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.onFailure {
            if (it is OperationCanceledException) throw it
            Timber.tag(TAG).w(it, "activeQueueCount failed")
        }.getOrDefault(0)
    }

    /**
     * 统计当前下载相关持久化数据的空间占用详情（点进管理弹窗时精确统计用，抑制长耗时误报）。
     */
    @JvmStatic
    @WorkerThread
    @JvmOverloads
    fun computeStorageStats(
        context: Context,
        cancellationSignal: CancellationSignal? = null,
    ): StorageStats {
        cancellationSignal?.throwIfCanceled()
        DbCallTracker.beginLongOperation()
        try {
            var recordsBytes = 0L
            var recordsCount = 0
            var queueBytes = 0L
            var queueCount = 0
            var stagingBytes = 0L

            runCatching {
                val db = AppDatabase.getAppDatabase(context)
                cancellationSignal?.throwIfCanceled()
                val raw = db.openHelper.writableDatabase
                cancellationSignal?.throwIfCanceled()
                raw.query(
                    SimpleSQLiteQuery("SELECT IFNULL(SUM(LENGTH(CAST(illustGson AS BLOB))), 0), COUNT(*) FROM illust_download_table"),
                    cancellationSignal
                ).use { c ->
                    if (c.moveToFirst()) {
                        recordsBytes = c.getLong(0)
                        recordsCount = c.getInt(1)
                    }
                }
                cancellationSignal?.throwIfCanceled()
                raw.query(
                    SimpleSQLiteQuery("SELECT IFNULL(SUM(LENGTH(CAST(illustGson AS BLOB))), 0), COUNT(*) FROM download_queue WHERE status = '${QueueStatus.SUCCESS}' AND (illustGson IS NOT NULL OR errorMsg IS NOT NULL)"),
                    cancellationSignal
                ).use { c ->
                    if (c.moveToFirst()) {
                        queueBytes = c.getLong(0)
                        queueCount = c.getInt(1)
                    }
                }
            }.onFailure {
                if (it is OperationCanceledException) throw it
                Timber.tag(TAG).w(it, "computeStorageStats db query failed")
            }

            cancellationSignal?.throwIfCanceled()

            runCatching {
                stagingBytes = dirSize(File(context.cacheDir, "staging_dl"), cancellationSignal)
            }.onFailure {
                if (it is OperationCanceledException) throw it
                Timber.tag(TAG).w(it, "computeStorageStats staging_dl failed")
            }

            val total = recordsBytes + queueBytes + stagingBytes
            return StorageStats(
                downloadRecordsBytes = recordsBytes,
                downloadRecordsCount = recordsCount,
                queuePayloadBytes = queueBytes,
                queueSuccessCount = queueCount,
                stagingBytes = stagingBytes,
                totalBytes = total
            )
        } finally {
            DbCallTracker.endLongOperation()
        }
    }

    /**
     * 双端极速采样估算法：通过覆盖索引快速获取总行数，对大表仅采样头尾少量记录计算均值，
     * 避免全盘遍历大体积 JSON 导致卡顿（毫秒级返回）。
     */
    @JvmStatic
    @WorkerThread
    @JvmOverloads
    fun estimateStorageStats(
        context: Context,
        cancellationSignal: CancellationSignal? = null,
    ): StorageStats {
        cancellationSignal?.throwIfCanceled()
        var recordsBytes = 0L
        var recordsCount = 0
        var queueBytes = 0L
        var queueCount = 0
        var stagingBytes = 0L

        runCatching {
            val db = AppDatabase.getAppDatabase(context)
            cancellationSignal?.throwIfCanceled()
            val raw = db.openHelper.writableDatabase
            cancellationSignal?.throwIfCanceled()

            // 1. 快速读取记录行数（走 index_illust_download_table_illustId 覆盖索引，毫秒级）
            raw.query(
                SimpleSQLiteQuery("SELECT COUNT(*) FROM illust_download_table"),
                cancellationSignal
            ).use { c ->
                if (c.moveToFirst()) {
                    recordsCount = c.getInt(0)
                }
            }

            cancellationSignal?.throwIfCanceled()

            if (recordsCount > 0) {
                if (recordsCount <= 200) {
                    // 小表：直接精确计算，耗时极短
                    raw.query(
                        SimpleSQLiteQuery("SELECT IFNULL(SUM(LENGTH(CAST(illustGson AS BLOB))), 0) FROM illust_download_table"),
                        cancellationSignal
                    ).use { c ->
                        if (c.moveToFirst()) {
                            recordsBytes = c.getLong(0)
                        }
                    }
                } else {
                    // 大表：双端采样（头 50 条 + 尾 50 条），仅读取 B-Tree 两端叶子节点，毫秒级
                    val sampleSql = """
                        SELECT IFNULL(AVG(len), 0) FROM (
                            SELECT len FROM (
                                SELECT LENGTH(CAST(illustGson AS BLOB)) AS len FROM illust_download_table ORDER BY rowid ASC LIMIT 50
                            )
                            UNION ALL
                            SELECT len FROM (
                                SELECT LENGTH(CAST(illustGson AS BLOB)) AS len FROM illust_download_table ORDER BY rowid DESC LIMIT 50
                            )
                        )
                    """.trimIndent()
                    raw.query(
                        SimpleSQLiteQuery(sampleSql),
                        cancellationSignal
                    ).use { c ->
                        if (c.moveToFirst()) {
                            val avgLen = c.getDouble(0)
                            recordsBytes = (avgLen * recordsCount).toLong()
                        }
                    }
                }
            }

            cancellationSignal?.throwIfCanceled()

            // 2. 队列表（若有残留才查，有索引）
            raw.query(
                SimpleSQLiteQuery("SELECT COUNT(*) FROM download_queue WHERE status = '${QueueStatus.SUCCESS}' AND (illustGson IS NOT NULL OR errorMsg IS NOT NULL)"),
                cancellationSignal
            ).use { c ->
                if (c.moveToFirst()) {
                    queueCount = c.getInt(0)
                }
            }

            if (queueCount > 0) {
                if (queueCount <= 100) {
                    raw.query(
                        SimpleSQLiteQuery("SELECT IFNULL(SUM(LENGTH(CAST(illustGson AS BLOB))), 0) FROM download_queue WHERE status = '${QueueStatus.SUCCESS}' AND (illustGson IS NOT NULL OR errorMsg IS NOT NULL)"),
                        cancellationSignal
                    ).use { c ->
                        if (c.moveToFirst()) {
                            queueBytes = c.getLong(0)
                        }
                    }
                } else {
                    val queueSampleSql = """
                        SELECT IFNULL(AVG(len), 0) FROM (
                            SELECT len FROM (
                                SELECT LENGTH(CAST(illustGson AS BLOB)) AS len FROM download_queue WHERE status = '${QueueStatus.SUCCESS}' AND (illustGson IS NOT NULL OR errorMsg IS NOT NULL) ORDER BY rowid ASC LIMIT 25
                            )
                            UNION ALL
                            SELECT len FROM (
                                SELECT LENGTH(CAST(illustGson AS BLOB)) AS len FROM download_queue WHERE status = '${QueueStatus.SUCCESS}' AND (illustGson IS NOT NULL OR errorMsg IS NOT NULL) ORDER BY rowid DESC LIMIT 25
                            )
                        )
                    """.trimIndent()
                    raw.query(
                        SimpleSQLiteQuery(queueSampleSql),
                        cancellationSignal
                    ).use { c ->
                        if (c.moveToFirst()) {
                            val avgLen = c.getDouble(0)
                            queueBytes = (avgLen * queueCount).toLong()
                        }
                    }
                }
            }
        }.onFailure {
            if (it is OperationCanceledException) throw it
            Timber.tag(TAG).w(it, "estimateStorageStats db query failed")
        }

        cancellationSignal?.throwIfCanceled()

        runCatching {
            stagingBytes = dirSize(File(context.cacheDir, "staging_dl"), cancellationSignal)
        }.onFailure {
            if (it is OperationCanceledException) throw it
            Timber.tag(TAG).w(it, "estimateStorageStats staging_dl failed")
        }

        // 用库文件真实大小做上限兜底，避免估算值超出物理文件
        runCatching {
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (dbFile.exists()) {
                val dbLen = dbFile.length()
                if (recordsBytes > dbLen) {
                    recordsBytes = dbLen
                }
            }
        }

        val total = recordsBytes + queueBytes + stagingBytes
        return StorageStats(
            downloadRecordsBytes = recordsBytes,
            downloadRecordsCount = recordsCount,
            queuePayloadBytes = queueBytes,
            queueSuccessCount = queueCount,
            stagingBytes = stagingBytes,
            totalBytes = total
        )
    }

    /**
     * 估算当前关联数据的总大小（用于 Settings 列表右侧的摘要提示，采用双端采样估算法）。
     */
    @JvmStatic
    @WorkerThread
    @JvmOverloads
    fun computeReclaimableBytes(
        context: Context,
        cancellationSignal: CancellationSignal? = null,
    ): Long {
        return estimateStorageStats(context, cancellationSignal).totalBytes
    }

    @JvmStatic
    @WorkerThread
    @JvmOverloads
    fun estimateReclaimableBytes(
        context: Context,
        cancellationSignal: CancellationSignal? = null,
    ): Long {
        return estimateStorageStats(context, cancellationSignal).totalBytes
    }

    /**
     * 在保留所有下载记录的前提下执行深度瘦身：
     * 1. 清空废弃列 taskGson；
     * 2. 清空 download_queue 中 SUCCESS 状态行的残留载荷；
     * 3. 逐批轻量化 illust_download_table 中的历史 JSON；
     * 4. 清理 staging_dl 临时文件；
     * 5. 执行 WAL Checkpoint 与 VACUUM 回收磁盘物理碎片。
     */
    @JvmStatic
    @WorkerThread
    fun deepSlim(context: Context): SlimResult {
        if (!busy.compareAndSet(false, true)) {
            Timber.tag(TAG).w("deepSlim() 已在执行，忽略本次调用")
            return SlimResult()
        }

        DbCallTracker.beginLongOperation()
        try {
            Timber.tag(TAG).i("deepSlim() started")

            val queue = context.appServices().queueDownloadManager
            runCatching { queue.pause() }
                .onFailure { Timber.tag(TAG).w(it, "QueueDownloadManager.pause failed") }

            val db = AppDatabase.getAppDatabase(context)
            val raw = db.openHelper.writableDatabase

            var rowsProcessed = 0
            var rowsSlimmed = 0
            var bytesSaved = 0L
            var complete = true

            // 1. 清理 taskGson 废弃字段
            runCatching {
                raw.execSQL("UPDATE illust_download_table SET taskGson = NULL WHERE taskGson IS NOT NULL")
            }.onFailure {
                complete = false
                Timber.tag(TAG).w(it, "Clear taskGson failed")
            }

            // 2. 清理已完成任务在队列中的残余载荷
            var queueRowsCleared = 0
            runCatching {
                raw.query("SELECT COUNT(*) FROM download_queue WHERE status = '${QueueStatus.SUCCESS}' AND (illustGson IS NOT NULL OR errorMsg IS NOT NULL)").use { cursor ->
                    if (cursor.moveToFirst()) {
                        queueRowsCleared = cursor.getInt(0)
                    }
                }
                raw.execSQL("UPDATE download_queue SET illustGson = NULL, errorMsg = NULL WHERE status = '${QueueStatus.SUCCESS}' AND (illustGson IS NOT NULL OR errorMsg IS NOT NULL)")
            }.onFailure {
                complete = false
                Timber.tag(TAG).w(it, "Clear download_queue payload failed")
            }

            // 3. 按主键索引流式逐批瘦身历史记录
            runCatching {
                var lastFileName = ""
                val batchSize = 300

                while (true) {
                    val batch = mutableListOf<Pair<String, String>>()
                    raw.query(
                        "SELECT fileName, illustGson FROM illust_download_table WHERE fileName > ? AND illustGson IS NOT NULL ORDER BY fileName ASC LIMIT ?",
                        arrayOf(lastFileName, batchSize.toString())
                    ).use { cursor ->
                        val nameIdx = cursor.getColumnIndexOrThrow("fileName")
                        val gsonIdx = cursor.getColumnIndexOrThrow("illustGson")
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIdx)
                            val gson = cursor.getString(gsonIdx)
                            if (name != null && gson != null) {
                                batch.add(name to gson)
                            }
                        }
                    }

                    if (batch.isEmpty()) break

                    val updates = mutableListOf<Pair<String, String>>()
                    for ((name, original) in batch) {
                        rowsProcessed++
                        val slimmed = DownloadRecordSlim.slimDownloadJson(name, original)
                        if (slimmed != null && slimmed.length < original.length) {
                            bytesSaved += (original.length - slimmed.length)
                            updates.add(name to slimmed)
                        }
                    }

                    if (updates.isNotEmpty()) {
                        raw.beginTransaction()
                        try {
                            val stmt = raw.compileStatement("UPDATE illust_download_table SET illustGson = ? WHERE fileName = ?")
                            for ((name, slimmed) in updates) {
                                stmt.bindString(1, slimmed)
                                stmt.bindString(2, name)
                                stmt.executeUpdateDelete()
                            }
                            raw.setTransactionSuccessful()
                        } finally {
                            raw.endTransaction()
                        }
                        rowsSlimmed += updates.size
                    }

                    lastFileName = batch.last().first
                }
            }.onFailure {
                complete = false
                Timber.tag(TAG).w(it, "Batch slimming illust_download_table failed")
            }

            // 4. 清除临时 stage 文件夹
            runCatching {
                val stage = File(context.cacheDir, "staging_dl")
                if (stage.exists()) stage.deleteRecursively()
            }.onFailure { Timber.tag(TAG).w(it, "staging_dl wipe failed") }

            // 5. 整理数据库并回收物理存储空间。
            //    这里的 VACUUM 与 wipe 不同，不能省：auto_vacuum=FULL 只归还整页空闲页，wipe 的
            //    DELETE 留下的正是整页；瘦身是原地改小，留下的是半空的叶子页，它合并不了。实测
            //    398MB 的表瘦身后只做 checkpoint 剩 53MB，再 VACUUM 剩 20MB。它跑在瘦身之后，
            //    此时库已经缩小，耗时和临时空间都按缩小后的体积算；同时受 busy 单飞闸保护。
            runCatching {
                raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                raw.execSQL("VACUUM")
                raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }.onFailure {
                complete = false
                Timber.tag(TAG).w(it, "VACUUM failed")
            }

            // 6. 通知 UI 刷新
            runCatching { ManagerReactive.pokeDoneTable() }
            runCatching { queue.queueListInvalidations.tryEmit(Unit) }

            Timber.tag(TAG).i("deepSlim() done: processed=$rowsProcessed, slimmed=$rowsSlimmed, queueCleared=$queueRowsCleared, bytesSaved=$bytesSaved")
            return SlimResult(rowsProcessed, rowsSlimmed, queueRowsCleared, bytesSaved, complete)
        } finally {
            DbCallTracker.endLongOperation()
            busy.set(false)
        }
    }

    /**
     * 把下载管理关联的持久化数据全部清掉。
     *
     * @return 库侧三步是否全部成功。false 表示有表没清干净。
     */
    @JvmStatic
    @WorkerThread
    fun wipe(context: Context): Boolean {
        if (!busy.compareAndSet(false, true)) {
            Timber.tag(TAG).w("wipe() 已在执行，忽略本次调用")
            return false
        }

        DbCallTracker.beginLongOperation()
        try {
            Timber.tag(TAG).i("wipe() started")

            val queue = context.appServices().queueDownloadManager
            runCatching { queue.pause() }
                .onFailure { Timber.tag(TAG).w(it, "QueueDownloadManager.pause failed") }

            var dbOk = true
            runCatching { Manager.get().clearAll() }
                .onFailure {
                    dbOk = false
                    Timber.tag(TAG).e(it, "Manager.clearAll failed")
                }

            val db = AppDatabase.getAppDatabase(context)
            runCatching { db.downloadDao().deleteAllDownload() }
                .onFailure {
                    dbOk = false
                    Timber.tag(TAG).e(it, "deleteAllDownload failed")
                }
            runCatching { runBlocking { db.downloadQueueDao().deleteAll() } }
                .onFailure {
                    dbOk = false
                    Timber.tag(TAG).e(it, "downloadQueue.deleteAll failed")
                }

            runCatching {
                val stage = File(context.cacheDir, "staging_dl")
                if (stage.exists()) stage.deleteRecursively()
            }.onFailure { Timber.tag(TAG).w(it, "staging_dl wipe failed") }

            if (dbOk) {
                runCatching {
                    val raw = db.openHelper.writableDatabase
                    raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                }.onFailure { Timber.tag(TAG).w(it, "wal_checkpoint failed") }
            }

            runCatching { ManagerReactive.pokeDoneTable() }
            runCatching { queue.queueListInvalidations.tryEmit(Unit) }

            Timber.tag(TAG).i("wipe() done, dbOk=%s", dbOk)
            return dbOk
        } finally {
            DbCallTracker.endLongOperation()
            busy.set(false)
        }
    }

    private fun dirSize(dir: File, cancellationSignal: CancellationSignal? = null): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            cancellationSignal?.throwIfCanceled()
            val f = stack.removeLast()
            if (f.isDirectory) {
                f.listFiles()?.forEach { stack.addLast(it) }
            } else {
                total += f.length()
            }
        }
        return total
    }
}
