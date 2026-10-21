package ceui.pixiv.ui.task

import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Novel
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.NovelSeriesDetail
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.header.HeaderConfigRepo
import ceui.pixiv.download.header.NovelHeaderRenderer
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.download.toast.DownloadToastKind
import ceui.pixiv.download.toast.DownloadToasts
import ceui.pixiv.ui.bulk.FetchEvent
import ceui.pixiv.ui.common.getTxtFileIdInDownloads
import ceui.pixiv.ui.novel.reader.export.EpubImageRetryPolicy
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.ExportUtils
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.witstudio.dialog.WitDialog
import com.blankj.utilcode.util.ActivityUtils
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单篇失败信息。`reason` 是一句能直接展示给用户的短原因（系列页的失败汇总弹窗用它）。
 */
data class FailedNovel(
    val novel: Novel,
    val reason: String?,
)

/** 批量（逐篇落盘）的进度。UI 只读，不写。 */
sealed interface NovelBatchState {
    val total: Int

    /** 已受理、还没拿到串行闸门。 */
    data class Preparing(override val total: Int) : NovelBatchState

    /** 系列「下载全部」在拉完整章节列表（此时 total 还未知）。 */
    data class Resolving(override val total: Int) : NovelBatchState

    data class Running(
        override val total: Int,
        val done: Int,
        val currentTitle: String,
    ) : NovelBatchState

    data class Finished(
        override val total: Int,
        val failures: List<FailedNovel>,
    ) : NovelBatchState

    data class Canceled(
        override val total: Int,
        val done: Int,
    ) : NovelBatchState
}

/** 终态判定：UI 订阅时用它决定什么时候解除按钮禁用 / 收起多选。 */
val NovelBatchState.isTerminal: Boolean
    get() = this is NovelBatchState.Finished || this is NovelBatchState.Canceled

/** 合并下载（整系列合成一个文件）的阶段。 */
enum class NovelMergeState { QUEUED, RUNNING, DONE, FAILED, CANCELED }

/**
 * 一次批量下载的句柄。**页面只是订阅方** —— 拿到它以后就算页面销毁，下载照跑，
 * 结果 toast 由 [NovelDownloadManager] 自己发。
 */
class NovelBatchJob internal constructor(
    private val job: Job,
    private val stateFlow: MutableStateFlow<NovelBatchState>,
) {
    val state: StateFlow<NovelBatchState> = stateFlow.asStateFlow()

    fun cancel() {
        job.cancel()
    }
}

/**
 * 一次合并下载的句柄。
 *
 * [events] 带 replay：弹窗（甚至退出后再进来的另一个弹窗）随时接入都能看到已经发生过的
 * 日志与终态，不需要"必须在任务开始前就在场"。取消走 [requestStop] —— 合作式，
 * 让 producer 把已抓到的章节写成截短版。
 *
 * [attach] / [detach] 记录"此刻有没有弹窗在看"：收尾时没人看就补一条 toast，
 * 否则用户退页之后这次合并会静默结束，成功失败都不知道。
 */
class NovelMergeSession internal constructor(
    val seriesId: Long,
    private val stopFlag: AtomicBoolean,
    private val eventFlow: MutableSharedFlow<FetchEvent>,
    private val stateFlow: MutableStateFlow<NovelMergeState>,
) {
    private var job: Job? = null
    private val watchers = AtomicInteger(0)

    val events: SharedFlow<FetchEvent> = eventFlow.asSharedFlow()
    val state: StateFlow<NovelMergeState> = stateFlow.asStateFlow()

    val isRunning: Boolean
        get() = state.value == NovelMergeState.QUEUED || state.value == NovelMergeState.RUNNING

    internal fun bindJob(runningJob: Job) {
        job = runningJob
    }

    /** 弹窗接入 / 离开（主线程）。 */
    internal fun attach() {
        watchers.incrementAndGet()
    }

    internal fun detach() {
        watchers.decrementAndGet()
    }

    internal fun hasWatcher(): Boolean = watchers.get() > 0

    /** 请求在下一个章节边界停下（已抓到的章节照常写盘）。 */
    fun requestStop() {
        stopFlag.set(true)
    }

    fun cancel() {
        job?.cancel()
    }
}

/**
 * 小说下载的唯一执行者。
 *
 * ## 为什么要有这个单例
 *
 * 以前小说侧四条链（批量逐篇 / 系列「下载全部」/ 系列合并 / 跨系列合并）全都跑在宿主
 * Activity 的 `lifecycleScope` 上。后果是：**退出页面 = 当场取消下载**，而且是静默的 ——
 * 任务被取消后 `onFinished` 根本不回调，连"已取消"的提示都没有，用户只看到进度 toast
 * 停在半路然后消失。插画侧没这个问题，因为它灌进 `download_queue`、由
 * [ceui.pixiv.ui.bulk.QueueDownloadManager] 的进程级 scope 消费；小说不进那张表，
 * 所以只能靠自己这条常驻 scope。
 *
 * 现在页面与弹窗一律退化成**订阅方**：拿 [NovelBatchJob.state] / [NovelMergeSession.events]
 * 渲染，页面死掉不产生任何副作用，下载结果由这里统一 toast。
 *
 * ## 串行闸门
 *
 * pixiv 对 `getNovelText` 很快回 429，各条链内部本来就有 1.5s 间隔。下载不再随页面退出之后，
 * 用户完全可能在前一条还在跑时再起一条（原来退出就把上一条杀了，所以撞不上）。[lane] 保证
 * 任何时刻只有一条链在打网络；要排队时会先提示一句，不让用户对着空气等。
 */
object NovelDownloadManager {

    /** 进程级作用域：宿主页面全销毁也不会被取消。 */
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 小说正文请求的串行闸门，见类注释。 */
    private val lane = Mutex()

    private const val ITEM_DELAY_MS = 1500L
    private const val SERIES_PAGE_DELAY_MS = 1000L

    /** 合并下载的 replay 容量：够弹窗中途接入时重建整段日志（弹窗自身只留 200 行）。 */
    private const val MERGE_REPLAY = 512

    private val appContext: Context get() = Shaft.getContext()

    // ── 批量（逐篇落盘）────────────────────────────────────────────────

    /**
     * 下载一批小说，每篇各写一个文件。
     *
     * [seriesPositions] / [seriesTotal] 只在"这一批来自同一个系列"时给：标出「第 X / Y 篇」
     * 并喂给路径模板的 `{series_order}`。多选下载时位置必须按**完整章节序列**算，
     * 不能按选中子集的下标（勾第 3、5、9 章时，文件里要的还是 3、5、9）。
     */
    fun startBatch(
        novels: List<Novel>,
        format: ExportFormat,
        seriesPositions: Map<Long, Int>? = null,
        seriesTotal: Int? = null,
    ): NovelBatchJob {
        val state = MutableStateFlow<NovelBatchState>(NovelBatchState.Preparing(novels.size))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runExclusive {
                    runBatchLocked(state, novels, format, seriesPositions, seriesTotal)
                }
            } catch (ce: CancellationException) {
                state.value = NovelBatchState.Canceled(
                    total = novels.size,
                    done = (state.value as? NovelBatchState.Running)?.done ?: 0,
                )
                throw ce
            }
        }
        val handle = NovelBatchJob(job, state)
        job.start()
        return handle
    }

    /**
     * 系列页「下载全部」：先把整个系列的章节拉全，再逐篇落盘。
     *
     * 拉列表这一段原来在 `FetchAllTask` 里、同样挂在 Activity 上 —— 用户点完就退，
     * 连列表都没拉完就没了。现在整段都在这条常驻 scope 里。
     */
    fun startSeriesDownloadAll(seriesId: Long, format: ExportFormat): NovelBatchJob {
        val state = MutableStateFlow<NovelBatchState>(NovelBatchState.Preparing(0))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runExclusive {
                    state.value = NovelBatchState.Resolving(0)
                    val novels = fetchAllSeriesNovels(seriesId)
                    if (novels.isEmpty()) {
                        state.value = NovelBatchState.Finished(0, emptyList())
                        withContext(Dispatchers.Main) {
                            DownloadToasts.show(
                                DownloadToastKind.NOVEL_SERIES_EXPORT,
                                appContext.getString(R.string.merge_download_failed_empty),
                            )
                        }
                        return@runExclusive
                    }
                    // 拉到的就是整个系列的有序章节，系列位置 = 下标 + 1。
                    val ordered = novels.distinctBy { it.id }
                    val positions = ordered.withIndex().associate { (i, n) -> n.id to i + 1 }
                    runBatchLocked(state, novels, format, positions, ordered.size)
                }
            } catch (ce: CancellationException) {
                state.value = NovelBatchState.Canceled(
                    total = state.value.total,
                    done = (state.value as? NovelBatchState.Running)?.done ?: 0,
                )
                throw ce
            }
        }
        val handle = NovelBatchJob(job, state)
        job.start()
        return handle
    }

    /** 已经在 [lane] 里时调用的批处理主体（不再自己抢闸门）。 */
    private suspend fun runBatchLocked(
        state: MutableStateFlow<NovelBatchState>,
        novels: List<Novel>,
        format: ExportFormat,
        seriesPositions: Map<Long, Int>?,
        seriesTotal: Int?,
    ) {
        val total = novels.size
        if (total == 0) {
            state.value = NovelBatchState.Finished(0, emptyList())
            return
        }
        val failures = mutableListOf<FailedNovel>()
        for ((index, novel) in novels.withIndex()) {
            state.value = NovelBatchState.Running(total, index, novel.title.orEmpty())
            val position = seriesPositions?.get(novel.id)
            try {
                withContext(Dispatchers.IO) {
                    downloadOne(
                        novel = novel,
                        format = format,
                        seriesIndex = position,
                        seriesTotal = seriesTotal.takeIf { position != null },
                    )
                }
            } catch (ce: CancellationException) {
                // 取消不是"这一篇失败"，别把它记进 failures（旧实现就是栽在这条上：
                // catch(Exception) 把 CancellationException 也吞成了 FailedNovel）。
                throw ce
            } catch (ex: Exception) {
                Timber.e(ex, "NovelDownloadManager: failed on ${novel.id} (${novel.title})")
                failures += FailedNovel(novel, ex.message ?: ex::class.java.simpleName)
            }
            val done = index + 1
            showProgress(done, total)
            if (done < total) delay(ITEM_DELAY_MS)
        }
        state.value = NovelBatchState.Finished(total, failures)
        showResult(failures)
    }

    // ── 合并下载（整系列一个文件）──────────────────────────────────────

    /**
     * 合并下载一个系列。[allowAutoEpub] 为 true 且默认格式是 TXT、正文含插图时，
     * 会向**当前前台 Activity** 质询 TXT/EPUB（页面死了就按原格式继续，不中断）。
     */
    fun startMerge(
        seriesDetail: NovelSeriesDetail,
        knownNovels: List<Novel>,
        format: ExportFormat,
        allowAutoEpub: Boolean = false,
    ): NovelMergeSession {
        val events = MutableSharedFlow<FetchEvent>(
            replay = MERGE_REPLAY,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val stopFlag = AtomicBoolean(false)
        val state = MutableStateFlow(NovelMergeState.QUEUED)
        val session = NovelMergeSession(seriesDetail.id, stopFlag, events, state)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (lane.isLocked) {
                    // 合并下载有自己的日志区，排队信息写在那里比弹 toast 更贴。
                    events.emit(FetchEvent.Log("> ⏳ 前一条小说下载还没结束，排队等待…"))
                }
                runExclusive(notify = false) {
                    state.value = NovelMergeState.RUNNING
                    MergeDownloadNovelSeriesTask(appContext).bulkMergeNovelSeries(
                        seriesDetail = seriesDetail,
                        knownNovels = knownNovels,
                        format = format,
                        stopSignal = stopFlag,
                        allowAutoEpub = allowAutoEpub,
                        confirmFormat = ::confirmMergeFormat,
                    ).collect { event ->
                        events.emit(event)
                        when (event) {
                            is FetchEvent.Done -> {
                                state.value = NovelMergeState.DONE
                                announceMergeResult(session, failed = false)
                            }
                            is FetchEvent.Errored -> {
                                val canceled = stopFlag.get()
                                state.value = if (canceled) {
                                    NovelMergeState.CANCELED
                                } else {
                                    NovelMergeState.FAILED
                                }
                                if (!canceled) announceMergeResult(session, failed = true)
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (ce: CancellationException) {
                state.value = NovelMergeState.CANCELED
                throw ce
            }
        }
        session.bindJob(job)
        job.start()
        return session
    }

    /**
     * 合并收尾时如果没有任何弹窗在看（用户已经退出页面），补一条 toast ——
     * 否则这次合并会静默结束，成功失败都没人知道。
     */
    private suspend fun announceMergeResult(session: NovelMergeSession, failed: Boolean) {
        if (session.hasWatcher()) return
        withContext(Dispatchers.Main) {
            DownloadToasts.show(
                DownloadToastKind.NOVEL_SERIES_EXPORT,
                appContext.getString(
                    if (failed) R.string.merge_novel_dialog_failed_title
                    else R.string.merge_novel_dialog_done_title
                ),
            )
        }
    }

    // ── 跨系列（作者系列列表页）────────────────────────────────────────

    /**
     * 给 [CrossSeriesDownloadTask] 这类"不是一次简单批处理"的链路用的入口：
     * 进程级 scope + 同一道串行闸门，block 在主线程跑（这些链路的耗时活都在内部
     * `withContext(Dispatchers.IO)` 里，主线程只做编排和 toast，和原来在
     * `activity.lifecycleScope` 上的线程模型一致）。
     */
    internal fun launchNovel(block: suspend () -> Unit): Job = scope.launch {
        runExclusive { withContext(Dispatchers.Main) { block() } }
    }

    // ── 串行闸门 ──────────────────────────────────────────────────────

    /**
     * 排队时要提示一句，否则用户对着不动的界面等几十秒。
     * [notify] 为 false 的调用方（合并下载）自己往日志区写一行，不必再弹 toast。
     */
    private suspend fun runExclusive(notify: Boolean = true, block: suspend () -> Unit) {
        if (lane.isLocked && notify) {
            withContext(Dispatchers.Main) {
                DownloadToasts.show(
                    DownloadToastKind.NOVEL_PROGRESS,
                    appContext.getString(R.string.novel_download_waiting_previous),
                )
            }
        }
        lane.withLock { block() }
    }

    // ── 章节列表 ──────────────────────────────────────────────────────

    /**
     * 拉一个系列的全部章节（分页 + 去重）。合并下载与「下载全部」共用同一份实现，
     * 保证两边的系列位置 / 篇数口径一致。
     */
    internal suspend fun fetchAllSeriesNovels(
        seriesId: Long,
        initial: List<Novel> = emptyList(),
        onEvent: suspend (FetchEvent) -> Unit = {},
    ): List<Novel> {
        val all = initial.toMutableList()
        var lastOrder: Int? = if (all.isEmpty()) null else all.size
        var safetyGuard = 0
        var pageIdx = 0
        while (safetyGuard < 50) {
            safetyGuard++
            pageIdx++
            onEvent(
                FetchEvent.Networking(
                    pageIdx,
                    "/v1/novel/series?series_id=$seriesId&last_order=${lastOrder ?: "—"}",
                )
            )
            val t0 = System.currentTimeMillis()
            val resp = try {
                Client.appApi.getNovelSeries(seriesId, lastOrder)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                onEvent(FetchEvent.Warning("getNovelSeries lastOrder=$lastOrder failed: ${ex.message}"))
                break
            }
            val latency = System.currentTimeMillis() - t0
            val page = resp.novels.orEmpty()
            onEvent(FetchEvent.PageReceived(pageIdx, page.size, latency, all.size + page.size))
            if (page.isEmpty()) break
            val existingIds = all.map { it.id }.toHashSet()
            val fresh = page.filter { it.id !in existingIds }
            if (fresh.isEmpty()) break
            all.addAll(fresh)
            if (resp.next_url.isNullOrEmpty()) break
            lastOrder = all.size
            delay(SERIES_PAGE_DELAY_MS)
        }
        return all
    }

    // ── 单篇落盘 ──────────────────────────────────────────────────────

    /**
     * 单篇落盘。异常一律往外抛，由调用方收成 [FailedNovel] —— 一篇坏掉不该中断整批。
     */
    private suspend fun downloadOne(
        novel: Novel,
        format: ExportFormat,
        seriesIndex: Int?,
        seriesTotal: Int?,
    ) {
        val ctx = appContext
        val destination: RelativePath = DownloadItems.novelDestinationFromLoxia(
            novel,
            extOverride = format.extension,
            seriesOrder = seriesIndex,
            seriesTotal = seriesTotal,
        )
        val fileName = destination.filename

        // 已下过的直接跳过。DownloadNovelTask 也是先做这步预检再碰网络。
        if (format == ExportFormat.Txt && getTxtFileIdInDownloads(ctx, fileName) != null) {
            Timber.d("$fileName already exists, skipping")
            return
        }

        val html = Client.appApi.getNovelText(novel.id).string()
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel
            ?: throw RuntimeException("invalid web novel")

        // 非 TXT 复用阅读器的导出链路，别在批量里再维护一套 MD/EPUB/PDF 实现。
        if (format != ExportFormat.Txt) {
            val tokens = ContentParser.tokenize(wNovel)
            when (val result = NovelExportManager.export(
                context = ctx,
                format = format,
                novel = novel,
                webNovel = wNovel,
                tokens = tokens,
                seriesOrder = seriesIndex,
                seriesTotal = seriesTotal,
                // 不传 activity：批量下载可能在页面已经销毁之后才跑到这里。
                // Batch 策略自己会取前台 Activity，取不到 / 已销毁就静默记为"有图失败"。
                retryPolicy = EpubImageRetryPolicy.Batch(),
            )) {
                is ExportResult.Success, is ExportResult.Skipped -> Unit
                is ExportResult.Failure -> throw RuntimeException(result.message)
            }
            return
        }

        val buffer = StringBuffer().apply {
            append("\n\n")
            append("<===== Shaft Novel Start =====>")
            append("\n\n")
            append(
                NovelHeaderRenderer.render(
                    novel = novel,
                    preset = HeaderConfigRepo.activePreset(),
                    isSeriesChapter = novel.series != null,
                    seriesIndex = seriesIndex,
                    seriesTotal = seriesTotal,
                )
            )
            append("\n")
            append("正文：")
            append("\n\n")
            append(DownloadNovelTask.replaceBrWithNewLine(wNovel.text))
            append("\n\n")
            append("<===== Shaft Novel End =====>")
            append("\n\n")
        }

        // null = 覆盖策略为「已存在则跳过」且目标已存在（上方预检只认 MediaStore Downloads，
        // SAF 等位置要到这里才知道）；写入失败直接抛，带着真实原因进 FailedNovel。
        val uri = ExportUtils.saveToDownloads(ctx, destination, format.mimeType) {
            it.write(buffer.toString().toByteArray(Charsets.UTF_8))
        }
        if (uri == null) Timber.d("$fileName already exists, skipping")
    }

    // ── 提示 ──────────────────────────────────────────────────────────

    private suspend fun showProgress(done: Int, total: Int) = withContext(Dispatchers.Main) {
        DownloadToasts.show(
            DownloadToastKind.NOVEL_PROGRESS,
            appContext.getString(R.string.batch_download_progress, done, total),
        )
    }

    /**
     * 终态 toast 由这里发，不由页面发：页面可能已经不在了，结果还是要让用户知道。
     */
    private suspend fun showResult(failures: List<FailedNovel>) = withContext(Dispatchers.Main) {
        DownloadToasts.show(
            DownloadToastKind.NOVEL_BATCH_RESULT,
            if (failures.isEmpty()) {
                appContext.getString(R.string.batch_download_all_ok)
            } else {
                appContext.getString(R.string.batch_download_some_failed, failures.size)
            },
        )
    }

    /**
     * 默认 TXT + 「有插图时存 EPUB」时的 TXT/EPUB 质询。
     *
     * 弹窗挂在**当前前台 Activity** 上而不是某个 fragment：下载已经和页面解耦，用户可能
     * 早就翻到别的页了。一个前台 Activity 都拿不到（退到后台）时按原格式继续 ——
     * 宁可少问一次，也不要把整单下载当成"用户取消"扔掉。
     */
    private suspend fun confirmMergeFormat(fallback: ExportFormat): ExportFormat? =
        withContext(Dispatchers.Main) {
            val act = ActivityUtils.getTopActivity()
            if (act == null || act.isFinishing || act.isDestroyed) return@withContext fallback
            var dialog: WitDialog? = null
            try {
                suspendCancellableCoroutine<ExportFormat?> { cont ->
                    val created = WitDialog.MenuDialogBuilder(act)
                        .setTitle(act.getString(R.string.setting_novel_epub_on_images))
                        .addItems(
                            arrayOf(
                                act.getString(R.string.format_txt),
                                act.getString(R.string.format_epub),
                            )
                        ) { d, which ->
                            if (cont.isActive) {
                                cont.resume(if (which == 0) ExportFormat.Txt else ExportFormat.Epub)
                            }
                            d.dismiss()
                        }
                        .addAction(act.getString(R.string.action_cancel)) { d, _ ->
                            if (cont.isActive) cont.resume(null)
                            d.dismiss()
                        }
                        .create()
                    dialog = created
                    created.setOnDismissListener { if (cont.isActive) cont.resume(null) }
                    created.show()
                }
            } finally {
                dialog?.dismiss()
            }
        }
}
