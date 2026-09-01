package ceui.pixiv.db.bulkclean

import android.content.Context
import androidx.annotation.WorkerThread
import ceui.lisa.core.Manager
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import ceui.pixiv.services.appServices

/**
 * 一键清理批量下载相关的持久化数据,把下载管理恢复成"第一次打开 app"的状态。
 *
 * 落盘的图片/小说文件不动 —— 用户只是丢失"下载历史 / 队列状态"两个 view,文件还在系统相册里。
 *
 * 清理对象:
 *   1. illust_download_table —— 已完成 tab 数据源,illustGson 列单条 ~30KB,
 *      重度用户能堆到 GB 级(本工具诞生的直接原因)。
 *   2. download_queue —— 批量队列状态机,SUCCESS 行不会自动清,illustGson 列也是 ~10KB。
 *   3. illust_downloading_table —— "下载中" tab 的持久化游标,正常情况下空。
 *   4. cache/staging_dl/ —— 批量下载写盘前的临时 stage 文件,异常退出可能残留。
 *
 * VACUUM 是关键 —— SQLite DELETE 只标 free page,文件大小不会回缩;不 VACUUM 用户在
 * 设置看到的"用户数据"数字不会变,反馈"清了等于没清"。1.6GB DB 上 VACUUM 大约 10–30s,
 * 调用方负责放进 progress dialog 里。
 *
 * **不要清**的:
 *   - mmkv/download_config_v1 (是用户配置,不是缓存)
 *   - 任何已下载文件 (在系统相册 / 用户自选目录,不归我们管)
 *   - 浏览历史 / 收藏 / 阅读统计 等其它表
 */
object BulkDownloadCacheCleaner {

    private const val TAG = "BulkDownloadCacheCleaner"

    /**
     * 估算当前会被本工具清掉的字节数(用于 Settings 右侧的提示)。
     * 取 illustGson 两列长度之和 + staging_dl/ 实际占用 —— 也就是用户能看到的"瘦身额度"。
     * SQLite 元数据(index、btree 内节点等)忽略不计,跟用户讲清楚的数字应当是"实际数据"。
     */
    @JvmStatic
    @WorkerThread
    fun computeReclaimableBytes(context: Context): Long {
        var bytes = 0L
        runCatching {
            val db = AppDatabase.getAppDatabase(context)
            bytes += db.downloadDao().sumIllustGsonBytes()
            bytes += runBlocking { db.downloadQueueDao().sumIllustGsonBytes() }
        }.onFailure { Timber.tag(TAG).w(it, "computeReclaimableBytes db query failed") }
        runCatching {
            bytes += dirSize(File(context.cacheDir, "staging_dl"))
        }.onFailure { Timber.tag(TAG).w(it, "computeReclaimableBytes staging_dl size failed") }
        return bytes
    }

    /**
     * 把上面"清理对象"列出的四项全部清掉,然后 VACUUM。
     * 调用前确保已经在 worker thread —— VACUUM 同步阻塞,1GB+ DB 上能跑几十秒,会 ANR。
     */
    @JvmStatic
    @WorkerThread
    fun wipe(context: Context) {
        Timber.tag(TAG).i("wipe() started")

        // 1) 停掉一切可能再往表里写的入口,否则我们 DELETE 完它马上又 INSERT 一条
        //    回来,既泄漏又让 VACUUM 报"database is locked"。
        //    Manager.clearAll() = stopAll() + deleteAllDownloading() + content.clear()
        //    + invalidate(),一并把内存里 DownloadItem 也清了(否则下载中 tab 残留
        //    "幽灵任务"直到下次切 tab)。
        val queue = context.appServices().queueDownloadManager
        runCatching { queue.pause() }
            .onFailure { Timber.tag(TAG).w(it, "QueueDownloadManager.pause failed") }
        runCatching { Manager.get().clearAll() }
            .onFailure { Timber.tag(TAG).w(it, "Manager.clearAll failed") }

        // 2) 清表 —— illust_downloading_table 已经在 clearAll() 里清过,这里只剩
        //    illust_download_table 跟 download_queue
        val db = AppDatabase.getAppDatabase(context)
        runCatching { db.downloadDao().deleteAllDownload() }
            .onFailure { Timber.tag(TAG).w(it, "deleteAllDownload failed") }
        runCatching { runBlocking { db.downloadQueueDao().deleteAll() } }
            .onFailure { Timber.tag(TAG).w(it, "downloadQueue.deleteAll failed") }

        // 3) staging_dl/ —— 异常退出残留的 .part 文件
        runCatching {
            val stage = File(context.cacheDir, "staging_dl")
            if (stage.exists()) stage.deleteRecursively()
        }.onFailure { Timber.tag(TAG).w(it, "staging_dl wipe failed") }

        // 4) VACUUM —— 关键步骤,不做的话文件大小不变,用户感觉"清了等于没清"。
        //    必须在所有 transaction 都已提交后调,Room 的 @Query 同步返回时事务已结束。
        //
        //    WAL 模式下前后各 checkpoint(TRUNCATE) 一次:
        //      - 前置:把 WAL 里挂着的旧 frame 全 fold 进主库,VACUUM 才能 reclaim 那部分页
        //      - 后置:VACUUM 自己也走 WAL,truncate 一下让 -wal 文件回到 0 字节,
        //        否则用户看到的"databases/"数字会因为残留的 -wal 而短期偏大
        runCatching {
            val raw = db.openHelper.writableDatabase
            raw.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            raw.execSQL("VACUUM")
            raw.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        }.onFailure { Timber.tag(TAG).w(it, "VACUUM failed") }

        // 5) 通知 UI —— 已完成 / 队列 tab 当前打开着的话立刻翻到空状态
        runCatching { ManagerReactive.pokeDoneTable() }
        runCatching { queue.queueListInvalidations.tryEmit(Unit) }

        Timber.tag(TAG).i("wipe() done")
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
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
