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
import com.hjq.toast.Toaster
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「试验性 · 日志文件」：把 Timber 日志落到**应用私有目录**，并在用户要求时导出分享。
 *
 * ## 为什么是私有目录，而不是「日志文件」桶（MediaStore）
 *
 * 这套东西存在的理由是**崩溃之后还能把日志拿出来**，而写进 MediaStore 做不到这一点：
 * 桶里的行在 `onFinish()` 之前一直是 `IS_PENDING=1`，而崩溃时进程直接死，没人去 finish；
 * 下一次冷启动 [ceui.pixiv.download.maintenance.MediaStoreOrphanCleaner] 会把上一会话遗留的
 * pending 行**全部删掉**（issue #857 的打扫环节，它没法也不该分辨哪一行是日志）。
 * 结果就是：唯一真正需要的那份日志，恰好是必定丢失的那份。
 *
 * 私有目录同时解决另外两件事：对没开这个开关的用户零可见性（不往他的「下载」目录里丢 txt），
 * 以及不受外部存储权限 / 用户手动清理的影响。
 *
 * ## 轮转策略：一个滚动日志 + 分代，而不是「每次启动一个新文件」
 *
 * 「每启动一个文件 + 最多留 N 个」看着简单，但保留的是**最近 N 次启动**：用户崩溃之后再开
 * 几次 app，崩溃日志就被挤掉了 —— 正好挤掉唯一有价值的那份。改成按**体积**轮转：
 * 始终追加到 [CURRENT_NAME]，超过 [MAX_FILE_BYTES] 就整体降一代（`.1` → `.2` → …），
 * 最老的一代删掉。于是「能回溯多久」取决于日志量而不是启动次数，崩溃日志能活到
 * 之后又写满 [MAX_FILE_BYTES] × [KEEP_GENERATIONS] 为止。上限也是硬的：最多
 * (KEEP_GENERATIONS + 1) × MAX_FILE_BYTES。
 *
 * ## 线程模型
 *
 * 打开 / 写入 / 轮转 / 导出全部在单线程 [ioExecutor] 上串行，主线程不碰磁盘；
 * 唯一的例外是崩溃时的 [logCrashNow]，它必须在崩溃线程上同步写完并 flush
 * —— 进程马上就没了，排队等 IO 线程等于不写。两者用同一把 [TimberFileTree] 内部锁互斥。
 */
object TimberFileLog {

    private const val TAG = "TimberFileLog"

    /** 日志目录（相对 `filesDir`）。也用于设置页那行小字。 */
    private const val LOG_DIR_NAME = "logs"

    /** 待写入行数上限，约 2MB 量级的字符串；超过就丢，见 [enqueueLog]。 */
    private const val MAX_PENDING_LINES = 10_000

    /**
     * 所有日志文件操作的串行执行器（daemon，不阻止进程退出）。
     *
     * **惰性创建**：开关关着的用户也会碰到本对象（进「设置 · 试验性」那一页就会读
     * [currentFolderPath]），不该为此白建一条线程挂在进程里。真正开始写日志时才建。
     */
    private val ioExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "timber-file-log").apply { isDaemon = true }
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 已入队待写、以及背压期间丢掉的行数，见 [enqueueLog]。 */
    private val pendingLines = AtomicInteger(0)
    private val droppedLines = AtomicInteger(0)

    @Volatile
    private var tree: TimberFileTree? = null

    /** 日志目录。`filesDir` 是应用私有的，卸载才清，系统不会替用户「打扫」。 */
    private fun logDir(context: Context): File =
        File(context.applicationContext.filesDir, LOG_DIR_NAME)

    /** 进程启动时调用：异步在 IO 线程打开日志文件并 plant 文件 Tree；已开启则忽略。 */
    fun maybeStart(context: Context) {
        val appContext = context.applicationContext
        ioExecutor.execute { maybeStartOnIoThread(appContext) }
    }

    private fun maybeStartOnIoThread(appContext: Context) {
        if (tree != null) return
        val treeToPlant = runCatching { TimberFileTree(logDir(appContext)) }.getOrNull() ?: return
        if (!treeToPlant.isOpen) return
        tree = treeToPlant
        try {
            Timber.plant(treeToPlant)
        } catch (plantError: Throwable) {
            tree = null
            runCatching { treeToPlant.close() }
        }
    }

    /** 当前日志所在目录（相对应用私有目录，如 `files/logs`）；未启用/未打开时为 null。 */
    fun currentFolderPath(): String? = if (tree?.isOpen == true) "files/$LOG_DIR_NAME" else null

    /**
     * 致命崩溃专用：在崩溃线程上**同步**写盘并 flush，不排队。
     *
     * flush 到内核就够了：进程死了页缓存还在，日志照样落地；逐行 fsync 的代价则完全不成比例。
     */
    fun logCrashNow(threadName: String, throwable: Throwable) {
        tree?.writeCrashNow(threadName, throwable)
    }

    /**
     * 导出并分享全部日志。
     *
     * **永远分享合并出来的副本**，不把正在追加写的那个文件递出去：接收方读到一半我们还在写，
     * 拿到的是个半截文件；而且分享给别的 app 的东西不该是我们的活动写入目标。
     */
    fun shareLogFile(context: Context) {
        val appContext = context.applicationContext
        ioExecutor.execute {
            // 先把缓冲里的行落盘，否则导出的日志缺最新的一段（崩溃前那几行往往最关键）。
            runCatching { tree?.flush() }
            val uri = runCatching { exportMergedLog(appContext) }.getOrNull()
            mainHandler.post {
                if (uri == null) {
                    toastShareFailed(context)
                } else {
                    startShare(context, uri)
                }
            }
        }
    }

    /** 把现存的各代日志按时间顺序合并成一个临时 txt，返回它的 FileProvider uri。 */
    private fun exportMergedLog(appContext: Context): Uri? {
        val files = LogFileRotation.existingOldestFirst(logDir(appContext))
        if (files.isEmpty()) return null
        val shareDir = File(appContext.cacheDir, SHARE_DIR_NAME).apply { mkdirs() }
        pruneOldShareFiles(shareDir)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(shareDir, "shaft-logs-$stamp.txt")
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            files.forEachIndexed { index, file ->
                if (index > 0) writer.write("\n")
                writer.write("===== ${file.name} =====\n")
                runCatching { file.bufferedReader(Charsets.UTF_8).use { it.copyTo(writer) } }
            }
        }
        return FileProvider.getUriForFile(appContext, appContext.packageName + ".provider", out)
    }

    /**
     * 清掉上次分享留下的副本。只删**足够旧**的：接收方可能是延迟读取 uri 的（笔记类 app
     * 常见），刚分享出去就删会让它读到空文件。
     */
    private fun pruneOldShareFiles(shareDir: File) {
        val deadline = System.currentTimeMillis() - SHARE_FILE_TTL_MS
        shareDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < deadline) runCatching { file.delete() }
        }
    }

    private fun startShare(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.setting_log_file_share))
            )
        } catch (e: Exception) {
            toastShareFailed(context)
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
     * **背压**：executor 用的是无界队列。日志速率长期高于落盘速率时，堆积的 Runnable 会把
     * 内存吃光。超过 [MAX_PENDING_LINES] 就丢弃并计数，等队列缓过来再补一行「丢了多少」——
     * 宁可日志缺一段，也不能因为一个试验性开关把 app OOM 掉。
     */
    fun enqueueLog(
        tree: TimberFileTree,
        ms: Long,
        threadName: String,
        priority: Int,
        tag: String?,
        message: String,
    ) {
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
                    tree.writeLine(
                        tree.formatLine(ms, threadName, Log.WARN, TAG, "队列积压，丢弃了 $dropped 行日志")
                    )
                }
                tree.writeLine(tree.formatLine(ms, threadName, priority, tag, message))
            }
        }.isSuccess
        if (!accepted) pendingLines.decrementAndGet()
    }

    private const val SHARE_DIR_NAME = "log-share"

    /** 分享副本的保留时长：比这更旧的在下次分享时清掉。 */
    private const val SHARE_FILE_TTL_MS = 60 * 60 * 1000L
}

/**
 * 实际的 [Timber.Tree]：向应用私有目录里的滚动日志追加，写满就分代轮转。
 *
 * 除 [writeCrashNow] 外的所有方法都跑在 [TimberFileLog] 的单线程 IO executor 上；
 * [writeCrashNow] 来自崩溃线程，用 [lock] 与写入 / 轮转互斥。
 */
class TimberFileTree internal constructor(private val dir: File) : Timber.Tree() {

    /** 写入、轮转、关闭三者互斥。崩溃时的同步写也走它，所以必须是可重入的 monitor。 */
    private val lock = Any()

    /** @Volatile：[isOpen] 从主线程读（设置页那行小字），写入/轮转在 IO 线程。 */
    @Volatile
    private var writer: PrintWriter? = null

    /** 只在持 [lock] 时读写。 */
    private var currentFile: File? = null

    /** 距上次查文件大小写了多少行，见 [rotateIfNeededLocked]。 */
    private var linesSinceSizeCheck = 0

    private val startRealtime = SystemClock.elapsedRealtime()

    init {
        synchronized(lock) { openCurrentLocked() }
    }

    val isOpen: Boolean get() = writer != null

    private fun openCurrentLocked() {
        val opened = runCatching {
            dir.mkdirs()
            val file = File(dir, LogFileRotation.CURRENT_NAME)
            // append=true：跨进程重启接着写同一份，崩溃前那一段才不会被下一次启动覆盖掉。
            val w = PrintWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), true)
            file to w
        }.getOrNull() ?: return
        currentFile = opened.first
        writer = opened.second
        linesSinceSizeCheck = 0
        // 会话分隔线：合并导出后一眼能看出哪段属于哪次启动。
        runCatching {
            opened.second.println(
                "===== session start ${SimpleDateFormat(STAMP, Locale.US).format(Date())} pid=${android.os.Process.myPid()} ====="
            )
        }
    }

    /**
     * 写一行。跑在 IO 线程。
     *
     * 大小检查每 [SIZE_CHECK_EVERY_LINES] 行做一次：`File.length()` 是一次 stat，单行一次太亏，
     * 而多写几百行再轮转对上限没有实质影响。
     */
    fun writeLine(line: String) {
        synchronized(lock) {
            val w = writer ?: return
            runCatching { w.println(line) }
            if (++linesSinceSizeCheck >= SIZE_CHECK_EVERY_LINES) {
                linesSinceSizeCheck = 0
                rotateIfNeededLocked()
            }
        }
    }

    /** 把缓冲刷到内核。导出前调用，保证分享出去的日志包含最新那几行。 */
    fun flush() {
        synchronized(lock) { runCatching { writer?.flush() } }
    }

    /**
     * 崩溃专用：在崩溃线程上同步写盘并 flush。
     *
     * 刻意不走 [TimberFileLog.enqueueLog]：进程马上就没了，排队等 IO 线程等于不写。
     */
    fun writeCrashNow(threadName: String, throwable: Throwable) {
        val ms = SystemClock.elapsedRealtime() - startRealtime
        val line = String.format(
            Locale.US,
            "[%8dms][%s][FATAL] %s",
            ms,
            threadName,
            Log.getStackTraceString(throwable),
        )
        synchronized(lock) {
            val w = writer ?: return
            runCatching {
                w.println(line)
                w.flush()
            }
        }
    }

    fun close() {
        synchronized(lock) {
            val w = writer ?: return
            runCatching { w.flush() }
            runCatching { w.close() }
            writer = null
            currentFile = null
        }
    }

    /**
     * 当前文件写满就整体降一代：最老的一代删掉，其余依次后移，当前文件变成 `.1`，再开一个新的。
     *
     * 调用方必须持有 [lock]。
     */
    private fun rotateIfNeededLocked() {
        val file = currentFile ?: return
        if (runCatching { file.length() }.getOrDefault(0L) < MAX_FILE_BYTES) return
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        LogFileRotation.rotate(dir)
        openCurrentLocked()
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
                priorityChar(priority),
            )
        )
        if (!tag.isNullOrEmpty()) {
            sb.append('[').append(tag).append(']')
        }
        sb.append(' ').append(message)
        return sb.toString()
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

    companion object {
        /** 单个日志文件写到多大就轮转。上限见 [LogFileRotation.KEEP_GENERATIONS]。 */
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024

        private const val SIZE_CHECK_EVERY_LINES = 256

        private const val STAMP = "yyyy-MM-dd HH:mm:ss"
    }
}
