package ceui.pixiv.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.download.sanitize.FsSanitizer
import ceui.pixiv.download.template.SafeTemplateRender
import ceui.pixiv.witstudio.dialog.WitDialog
import com.hjq.toast.Toaster
import timber.log.Timber
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 Timber 日志写入「日志文件」桶（[Bucket.Log]）的开关式文件日志。
 * 所有日志文件操作（打开/写入/关闭/合并）都在单线程 IO executor 上执行，
 * 避免在主线程做文件 I/O。
 */
object TimberFileLog {

    /** 所有日志文件操作的串行执行器（daemon，不阻止进程退出）。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "timber-file-log").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private const val TAG = "TimberFileLog"

    /** 待写入行数上限，约 2MB 量级的字符串；超过就丢，见 [enqueueLog]。 */
    private const val MAX_PENDING_LINES = 10_000

    /** 已入队待写、以及背压期间丢掉的行数，见 [enqueueLog]。 */
    private val pendingLines = AtomicInteger(0)
    private val droppedLines = AtomicInteger(0)

    @Volatile
    private var tree: TimberFileTree? = null

    /**
     * 本进程周期内创建过的日志文件 Uri（按创建顺序）。SAF 下不扫描目录，靠这份内存记录规避耗时扫描。
     *
     * ⚠️ 普通 ArrayList，**所有**读写必须持 `lifetimeUris` 这把锁：写在 IO 线程（每次开新日志
     * 文件都会 [register]，分享后会立刻重开一个），读在主线程（[shareLogFile]）。
     * 两边用不同的锁 = 一边 add 一边 toList，ArrayList 会抛 ConcurrentModificationException /
     * 越界，而这条路径在主线程上，崩的是整个 app。
     */
    private val lifetimeUris = mutableListOf<Uri>()

    /** 已分享过的文件数量：`lifetimeUris` 前 [lastSharedCount] 个视为已分享。与 [lifetimeUris] 同锁。 */
    private var lastSharedCount = 0

    /** 进程启动时调用：异步在 IO 线程打开并 plant 文件 Tree；已开启则忽略。 */
    fun maybeStart() {
        ioExecutor.execute { maybeStartOnIoThread() }
    }

    private fun maybeStartOnIoThread() {
        if (tree != null) return
        val treeToPlant = try {
            TimberFileTree()
        } catch (ignored: Throwable) {
            null
        } ?: return
        tree = treeToPlant
        try {
            Timber.plant(treeToPlant)
        } catch (plantError: Throwable) {
            tree = null
            runCatching { treeToPlant.close() }
        }
    }

    /** 记录新打开的日志文件 Uri（由 [TimberFileTree] 打开成功后调用，跑在 IO 线程）。 */
    fun register(uri: Uri) {
        synchronized(lifetimeUris) {
            if (uri !in lifetimeUris) {
                lifetimeUris += uri
            }
        }
    }

    /** 在 IO 线程执行：发布当前日志并重开新日志文件。调用方必须已在 IO executor 上。 */
    private fun publishOnIoThread() {
        stopOnIoThread()
        if (Shaft.sSettings?.isLogFileEnabled == true) {
            maybeStartOnIoThread()
        }
    }

    private fun stopOnIoThread() {
        val t = tree ?: return
        tree = null
        runCatching { Timber.uproot(t) }
        t.close()
    }

    /** 当前日志文件所在文件夹，如 `Shaft/Logs`；未启用/打开完成前为 null。 */
    fun currentFolderPath(): String? = tree?.currentFolderPath

    /** 致命崩溃专用：同步写盘并 flush，不等 IO executor，避免进程被杀前丢失。 */
    fun logCrashNow(threadName: String, throwable: Throwable) {
        tree?.writeCrashNow(threadName, throwable)
    }

    /**
     * 分享日志文件。
     * 第一次分享直接分享当前文件；本进程周期内已分享过时，用项目弹窗质询：
     *  - 分享新生成的日志；
     *  - 合并历史分享与新生成再分享。
     * 不扫描 SAF 目录，依赖 [lifetimeUris] 内存记录，避免 SAF 下耗时阻塞。
     */
    fun shareLogFile(context: Context) {
        // 一次取齐快照：分成两次读会在「刚好又开了一个新日志文件」时拿到互相错位的
        // all / lastSharedCount / size，算出错误的待分享区间。
        val all: List<Uri>
        val unshared: List<Uri>
        synchronized(lifetimeUris) {
            all = lifetimeUris.toList()
            unshared = all.drop(lastSharedCount)
        }
        if (unshared.isEmpty()) {
            toastShareFailed(context)
            return
        }
        if (all.any { it.scheme != "content" }) {
            toastShareFailed(context)
            return
        }
        val allSizeBefore = all.size
        if (lastSharedCount > 0) {
            WitDialog.MenuDialogBuilder(context)
                .setTitle(context.getString(R.string.setting_log_file_share_history_title))
                .addItems(
                    arrayOf(
                        context.getString(R.string.setting_log_file_share_since_last),
                        context.getString(R.string.setting_log_file_share_all)
                    )
                ) { dialog, which ->
                    dialog.dismiss()
                    // 点击弹窗选项后，才在 IO 线程触发 onFinish 发布，并合并/读取文件。
                    shareFilesOnIo(context, if (which == 0) unshared else all, allSizeBefore)
                }
                .show()
        } else {
            shareFilesOnIo(context, unshared, allSizeBefore)
        }
    }

    private fun shareFilesOnIo(context: Context, uris: List<Uri>, allSizeBefore: Int) {
        ioExecutor.execute {
            publishOnIoThread()
            synchronized(lifetimeUris) { lastSharedCount = allSizeBefore }

            val shareUri = if (uris.size > 1) {
                mergeToSingleFile(context, uris)
            } else {
                uris.singleOrNull()
            }
            mainHandler.post {
                if (shareUri == null || shareUri.scheme != "content") {
                    toastShareFailed(context)
                    return@post
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(
                        Intent.createChooser(
                            send,
                            context.getString(R.string.setting_log_file_share)
                        )
                    )
                } catch (e: Exception) {
                    toastShareFailed(context)
                }
            }
        }
    }

    /** 把多个日志文件内容合并成一个临时 txt，返回 FileProvider content:// Uri。 */
    private fun mergeToSingleFile(context: Context, uris: List<Uri>): Uri? {
        return try {
            val base = context.externalCacheDir ?: return null
            val dir = File(base, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "timber_merged_$stamp.txt")
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                uris.forEachIndexed { index, uri ->
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        if (index > 0) {
                            out.write("\n\n===== next log =====\n\n")
                        }
                        input.bufferedReader(Charsets.UTF_8).use { it.copyTo(out) }
                    }
                }
            }
            FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        } catch (e: Exception) {
            null
        }
    }

    private fun toastShareFailed(context: Context) {
        Toaster.show(context.getString(R.string.setting_log_file_share_failed))
    }

    /**
     * 由 [TimberFileTree.log] 调用：把一行日志投递到 IO 线程写入。
     *
     * **调用线程（绝大多数情况下是主线程）上只做两件便宜的事**：读一次 elapsedRealtime、
     * 读一次线程名，然后入队。`String.format` 与拼接都挪到 IO 线程 —— 打开这个开关之后
     * 全仓一千多个 Timber 调用点都会走到这里，格式化留在调用线程就是在 UI 线程上做
     * 无谓的分配。被背压丢掉的行因此连格式化的钱都不用花。
     *
     * **背压**：executor 用的是无界队列，而落盘端每行一次 flush（SAF / MediaStore 尤其慢）。
     * 日志速率长期高于落盘速率时，堆积的 Runnable 会把内存吃光。超过 [MAX_PENDING_LINES]
     * 就丢弃并计数，等队列缓过来再补一行「丢了多少」——宁可日志缺一段，也不能因为
     * 一个试验性开关把 app OOM 掉。
     */
    fun enqueueLog(tree: TimberFileTree, ms: Long, threadName: String, priority: Int, tag: String?, message: String) {
        if (pendingLines.get() >= MAX_PENDING_LINES) {
            droppedLines.incrementAndGet()
            return
        }
        pendingLines.incrementAndGet()
        val accepted = runCatching {
            ioExecutor.execute {
                pendingLines.decrementAndGet()
                val dropped = droppedLines.getAndSet(0)
                if (dropped > 0) {
                    tree.writeLine(tree.formatLine(ms, threadName, Log.WARN, TAG, "队列积压，丢弃了 $dropped 行日志"))
                }
                tree.writeLine(tree.formatLine(ms, threadName, priority, tag, message))
            }
        }.isSuccess
        if (!accepted) pendingLines.decrementAndGet()
    }
}

/**
 * 实际的 [Timber.Tree]：打开 `Bucket.Log` 桶的一个新文件并持续写入。
 * 构造（打开文件）与 [writeLine] / [close] 都运行在 [TimberFileLog] 的 IO executor 上。
 */
class TimberFileTree : Timber.Tree() {

    private val writer: PrintWriter?
    private val handle: StorageBackend.WriteHandle?
    private val relPath: RelativePath?
    private val startRealtime = SystemClock.elapsedRealtime()

    /** 当前日志文件所在文件夹（如 `Shaft/Logs`）；打开失败为 null。 */
    val currentFolderPath: String? get() = relPath?.directory?.joinToString("/")

    init {
        var h: StorageBackend.WriteHandle? = null
        var p: RelativePath? = null
        var w: PrintWriter? = null
        try {
            val config = DownloadsRegistry.store.loadOrFallback()
            val resolved = config.resolve(Bucket.Log)
            val meta = ItemMeta(
                id = 0L,
                title = "log",
                author = Author(0L, ""),
                createdAt = Instant.now(),
            )
            p = FsSanitizer.clean(
                SafeTemplateRender.render(
                    resolved.template,
                    Bucket.Log,
                    meta,
                    "txt",
                    config.pageNumbering,
                )
            )
            h = DownloadsRegistry.downloads.openRaw(
                Bucket.Log,
                p,
                "text/plain",
                OverwritePolicy.Replace,
            ) ?: error("openRaw returned null")
            w = PrintWriter(OutputStreamWriter(h.stream, Charsets.UTF_8), true)
        } catch (t: Throwable) {
            runCatching { h?.onAbort() }
            h = null
            p = null
            w = null
        }
        handle = h
        relPath = p
        writer = w
        if (w != null) {
            TimberFileLog.register(h!!.uri)
            log(Log.INFO, "TimberFileLog", "log file=${p!!.joinTo()}", null)
        }
    }

    /**
     * 跑在**调用线程**（任意线程，多数是主线程）：只取时间戳和线程名这两个必须就地读的值，
     * 其余一律交给 IO 线程，见 [TimberFileLog.enqueueLog]。
     */
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        TimberFileLog.enqueueLog(
            tree = this,
            ms = SystemClock.elapsedRealtime() - startRealtime,
            threadName = Thread.currentThread().name,
            priority = priority,
            tag = tag,
            // Timber 已在 prepareLog 阶段把 throwable 栈拼进 message，这里不要再重复追加。
            message = message,
        )
    }

    /** 拼成最终的一行。跑在 IO 线程。 */
    fun formatLine(ms: Long, threadName: String, priority: Int, tag: String?, message: String): String {
        val sb = StringBuilder(message.length + 48)
        sb.append(
            String.format(
                Locale.US,
                "[%8dms][%s][%c]",
                ms,
                threadName,
                priorityChar(priority)
            )
        )
        if (!tag.isNullOrEmpty()) {
            sb.append('[').append(tag).append(']')
        }
        sb.append(' ').append(message)
        return sb.toString()
    }

    /** 在 IO 线程写入一行。与崩溃同步写共用同一把锁，避免并发交错。 */
    fun writeLine(line: String) {
        val w = writer ?: return
        synchronized(w) {
            runCatching { w.println(line) }
        }
    }

    /** 崩溃专用：在任意线程直接同步写盘并 flush，避免进程被杀前异步任务丢失。 */
    fun writeCrashNow(threadName: String, throwable: Throwable) {
        val w = writer ?: return
        val ms = SystemClock.elapsedRealtime() - startRealtime
        val sb = StringBuilder()
        sb.append(
            String.format(
                Locale.US,
                "[%8dms][%s][FATAL] %s",
                ms,
                threadName,
                Log.getStackTraceString(throwable),
            )
        )
        synchronized(w) {
            runCatching {
                w.println(sb)
                w.flush()
            }
        }
    }

    fun close() {
        val w = writer ?: return
        runCatching { w.flush() }
        runCatching { w.close() }
        val h = handle
        if (h != null) {
            runCatching { h.onFinish() }
        }
    }

    private fun priorityChar(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }
}
