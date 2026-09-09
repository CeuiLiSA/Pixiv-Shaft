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

    @Volatile
    private var tree: TimberFileTree? = null

    /** 本进程周期内创建过的日志文件 Uri（按创建顺序）。SAF 下不扫描目录，靠这份内存记录规避耗时扫描。 */
    private val lifetimeUris = mutableListOf<Uri>()

    /** 已分享过的文件数量：`lifetimeUris` 前 [lastSharedCount] 个视为已分享。 */
    @Volatile
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

    /** 记录新打开的日志文件 Uri（由 [TimberFileTree] 打开成功后调用）。 */
    @Synchronized
    fun register(uri: Uri) {
        if (uri !in lifetimeUris) {
            lifetimeUris += uri
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
        val all = synchronized(lifetimeUris) { lifetimeUris.toList() }
        val unshared = all.drop(lastSharedCount)
        if (unshared.isEmpty()) {
            toastShareFailed(context)
            return
        }
        if (all.any { it.scheme != "content" }) {
            toastShareFailed(context)
            return
        }
        val allSizeBefore = synchronized(lifetimeUris) { lifetimeUris.size }
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
            lastSharedCount = allSizeBefore

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

    /** 由 [TimberFileTree.log] 调用：把格式化好的日志行投递到 IO 线程写入。 */
    fun enqueueLog(tree: TimberFileTree, line: String) {
        ioExecutor.execute {
            tree.writeLine(line)
        }
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

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val sb = StringBuilder()
        val ms = SystemClock.elapsedRealtime() - startRealtime
        sb.append(
            String.format(
                Locale.US,
                "[%8dms][%s][%c]",
                ms,
                Thread.currentThread().name,
                priorityChar(priority)
            )
        )
        if (!tag.isNullOrEmpty()) {
            sb.append('[').append(tag).append(']')
        }
        // Timber 已在 prepareLog 阶段把 throwable 栈拼进 message，这里不要再重复追加。
        sb.append(' ').append(message)
        TimberFileLog.enqueueLog(this, sb.toString())
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
