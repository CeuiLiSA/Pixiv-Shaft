package ceui.pixiv.db.bulkclean

import android.content.Context
import androidx.annotation.WorkerThread
import ceui.lisa.core.Manager
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.DbCallTracker
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
 * ## 为什么不再 VACUUM
 *
 * 这个库是 `auto_vacuum=FULL`(实测 `PRAGMA auto_vacuum` 返回 1):DELETE 提交时 SQLite
 * 自己会把空闲页挪到末尾并截断文件,不需要 VACUUM 回缩。实测 5000 行 / 97.8MB 的表
 * DELETE 后 page_count 25044 → 3、文件回到 0MB、freelist=0。
 *
 * VACUUM 反而有两个害处:
 *   - 它要把整个库复制到临时文件再回写,**峰值需要约一份库体积的额外空间**。用户的库
 *     本来就是几百 MB 到 GB 级,磁盘紧张时这一步会直接 `SQLITE_FULL`。
 *   - 它要**独占锁**,GB 级库上 10–30 秒。下载还在跑时执行,就是把写入和用户操作一起堵住;
 *     连点几次会叠出多个并发 VACUUM(旧实现没有重入保护),把连接池锁死,全 App 读库的
 *     界面一起空白。
 *
 * 现在只留两次 `wal_checkpoint(TRUNCATE)`:把 WAL 折回主库,让主库文件当场回缩 —— 它不占
 * 临时空间,失败也只是文件暂时不回缩,不影响"数据已清空"这个事实。
 *
 * ## 重入
 *
 * [wipe] 用 [wiping] 单飞。调用方(设置页)也有自己的闸,这里是最后一道:即使别处再调,
 * 也不会叠出第二个清理。
 *
 * **不要清**的:
 *   - mmkv/download_config_v1 (是用户配置,不是缓存)
 *   - 任何已下载文件 (在系统相册 / 用户自选目录,不归我们管)
 *   - 浏览历史 / 收藏 / 阅读统计 等其它表
 */
object BulkDownloadCacheCleaner {

    /**
     * 日志 tag 必须 ≤ 23 字符：超过这个长度 `Log.println` 在部分 Android 版本会直接抛
     * `IllegalArgumentException`（不只是 lint 噪音）。原来写的是类名全称（24 字符）。
     */
    private const val TAG = "BulkDlCacheCleaner"

    /** 单飞闸:清理是"全清"语义,叠两个只会互相抢锁。 */
    private val wiping = AtomicBoolean(false)

    /**
     * 批量队列里还有多少条没结束的行(`status != SUCCESS`)。
     *
     * 给设置页那道"队列不为空就别清"的闸用 —— 清理会打断下载,而下载中的写入又正好是
     * VACUUM/大事务最容易撞上的东西。走 `status` 索引,GB 级库上也是毫秒级。
     */
    @JvmStatic
    @WorkerThread
    fun activeQueueCount(context: Context): Int =
        runCatching {
            runBlocking { AppDatabase.getAppDatabase(context).downloadQueueDao().countActive() }
        }.onFailure { Timber.tag(TAG).w(it, "activeQueueCount failed") }.getOrDefault(0)

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
     * 把上面"清理对象"列出的四项全部清掉。
     *
     * @return **库侧三步是否全部成功**。false 表示有表没清干净 —— 调用方必须据此报失败,
     *         不能像旧实现那样无条件弹"已清除"。
     *         文件系统(staging_dl)的失败不计入:它不影响"下载记录已清空"这个结果。
     *
     * 调用前确保已经在 worker thread —— 大表的 DELETE 是同步阻塞的。
     */
    @JvmStatic
    @WorkerThread
    fun wipe(context: Context): Boolean {
        if (!wiping.compareAndSet(false, true)) {
            Timber.tag(TAG).w("wipe() 已在执行,忽略这次调用")
            return false
        }
        // 清大表是「我们自己知道要慢」的合法操作，别让看门狗把它当卡死弹窗。
        DbCallTracker.beginLongOperation()
        try {
            Timber.tag(TAG).i("wipe() started")

            // 1) 停掉一切可能再往表里写的入口,否则我们 DELETE 完它马上又 INSERT 一条
            //    回来,既泄漏又让后续 DELETE 反复撞锁。
            //    Manager.clearAll() = stopAll() + deleteAllDownloading() + content.clear()
            //    + invalidate(),一并把内存里 DownloadItem 也清了(否则下载中 tab 残留
            //    "幽灵任务"直到下次切 tab)。
            val queue = context.appServices().queueDownloadManager
            runCatching { queue.pause() }
                .onFailure { Timber.tag(TAG).w(it, "QueueDownloadManager.pause failed") }

            // 库侧三步,任何一步失败都算"没清干净"
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

            // 2) staging_dl/ —— 异常退出残留的 .part 文件。文件系统的事,不计入 dbOk。
            runCatching {
                val stage = File(context.cacheDir, "staging_dl")
                if (stage.exists()) stage.deleteRecursively()
            }.onFailure { Timber.tag(TAG).w(it, "staging_dl wipe failed") }

            // 3) checkpoint —— 不占临时空间,只把 WAL 折回主库让文件当场回缩。
            //    auto_vacuum=FULL 会在 DELETE 提交时把空闲页挪到末尾,checkpoint 之后主库
            //    文件就真的变小了,不需要 VACUUM。失败只影响文件何时回缩,不影响数据已清空。
            if (dbOk) {
                runCatching {
                    val raw = db.openHelper.writableDatabase
                    // wal_checkpoint 会返回一行结果，Android 的 execSQL 遇到带结果行的语句直接抛，
                    // 只能走 query；cursor 是惰性的，moveToFirst 才真正执行。
                    raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                }.onFailure { Timber.tag(TAG).w(it, "wal_checkpoint failed") }
            }

            // 4) 通知 UI —— 已完成 / 队列 tab 当前打开着的话立刻翻到空状态
            runCatching { ManagerReactive.pokeDoneTable() }
            runCatching { queue.queueListInvalidations.tryEmit(Unit) }

            Timber.tag(TAG).i("wipe() done, dbOk=%s", dbOk)
            return dbOk
        } finally {
            DbCallTracker.endLongOperation()
            wiping.set(false)
        }
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
