package ceui.pixiv.ui.bulk

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import ceui.lisa.activities.TemplateActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.pixiv.ui.task.NovelMergeSession
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * Claude Code CLI 风格的进度 dialog —— 用户能 **一直** 看到 "当前在干嘛"，绝不让人干等。
 *
 * 关键体感：
 *   - 顶部状态行 100ms 一刷，包含：
 *       {spinner 帧} {当前操作描述}  ·  page=N/total  ·  elapsed  ·  rate
 *   - 网络/DB/池/速率限制 / 章节合并 / 插画抓取 / 文件写入 都有独立子文案
 *   - rate-limit 实时倒计时 "1.20s 后继续"
 *   - 日志区记录每一个微动作（page received / latency / db ms / pool ms / cumulative）
 *
 * 通用化：通过 [Config] 把「标题 / header cmd / 名词 / 终态文案 / cancel 行为」抽出来。
 * Batch illust 走的是 [Config.batchIllustDefault]（行为完全跟旧版一致）；novel
 * merge 走 [Config.novelMergeDefault]（章节 / 插画 / 截短输出语义）。
 */
class FetchProgressDialog : DialogFragment(R.layout.dialog_fetch_progress) {

    private var flow: Flow<FetchEvent>? = null
    private var session: NovelMergeSession? = null
    private var fetchJob: Job? = null
    private var config: Config = Config()

    private lateinit var titleView: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusLine: TextView
    private lateinit var statusMetrics: TextView
    private lateinit var cancelBtn: Button
    private lateinit var openManagerBtn: Button
    private lateinit var closeBtn: Button

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val ringBuffer = ArrayDeque<String>(MAX_LINES + 4)
    @Volatile private var viewAlive: Boolean = false

    // —— 状态机 ——
    private enum class Phase {
        IDLE, NETWORKING, RECEIVED, DB, ENQUEUED, RATE_LIMIT,
        MERGING_CHAPTER, FETCHING_IMAGE, WRITING_FILE,
        DONE, FAILED, CANCELED,
    }
    @Volatile private var phase: Phase = Phase.IDLE
    @Volatile private var phaseDetail: String = ""
    @Volatile private var rateLimitUntil: Long = 0L
    @Volatile private var pageIndex: Int = 0
    @Volatile private var totalSoFar: Int = 0
    @Volatile private var startedAt: Long = 0L
    @Volatile private var spinnerFrame: Int = 0
    /** 进终态后冻结，refreshStatusLine 不再重新计算 elapsed，spinner ticker 也停止。 */
    @Volatile private var frozenElapsedMs: Long? = null

    /** 用户已按 cancel 按钮(COOPERATIVE 模式下让 status line 显示「stopping…」)。 */
    @Volatile private var stopRequested: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认允许 back / 点外部收起 dialog —— batch illust 的活儿已落进持久化队列，
        // dialog 关掉也由 QueueDownloadManager 接着下。
        //
        // merge novel 也已经是进程级会话（NovelDownloadManager），关窗口不再等于丢单；
        // 但仍然用 keepOpenUntilDone 锁住：它是唯一能看进度、也是唯一能按「取消」的地方，
        // 误关之后用户既看不到还剩几章，也没地方停（只能等它自己跑完）。
        isCancelable = !config.keepOpenUntilDone
    }

    fun bindFlow(flow: Flow<FetchEvent>) {
        this.flow = flow
    }

    /**
     * 绑定一个**进程级**会话（小说合并下载）。
     *
     * 与 [bindFlow] 的区别不在渲染，而在谁持有 producer：flow 版本由本 dialog 在
     * activity scope 里 collect，dialog 一关就断（插画批量入的是持久化队列，断了也没事）；
     * session 版本的生产者在 `NovelDownloadManager` 的进程级 scope 里，本 dialog 只是
     * 订阅方 —— 关窗口、退页面都不会影响下载，重新打开还能从 replay 里看到完整日志。
     */
    fun bindSession(session: NovelMergeSession) {
        this.session = session
    }

    fun bindConfig(config: Config) {
        this.config = config
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewAlive = true
        titleView = view.findViewById(R.id.title)
        logText = view.findViewById(R.id.logText)
        logScroll = view.findViewById(R.id.logScroll)
        statusLine = view.findViewById(R.id.statusLine)
        statusMetrics = view.findViewById(R.id.statusMetrics)
        cancelBtn = view.findViewById(R.id.cancelBtn)
        openManagerBtn = view.findViewById(R.id.openManagerBtn)
        closeBtn = view.findViewById(R.id.closeBtn)

        titleView.text = config.title
        statusLine.text = "${SPINNER[0]} starting…"
        statusMetrics.text = "${config.stepNoun}=— · total=0 · elapsed=0s · —"
        appendLine(config.headerCmd)
        appendLine("  " + getString(config.closeHintRes))
        flushLog()

        cancelBtn.setOnClickListener {
            config.onCancelRequested()
            when (config.cancelMode) {
                CancelMode.HARD -> {
                    // 会话的生产者跑在 NovelDownloadManager 的 scope 里，本 dialog 的
                    // fetchJob 只是订阅，取消它停不了下载。
                    session?.cancel() ?: fetchJob?.cancel()
                    phase = Phase.CANCELED
                    phaseDetail = "user canceled"
                    freezeTimer()
                    appendLine(getString(config.canceledLineRes))
                    cancelBtn.visibility = View.GONE
                    showCloseButton(terminal = true)
                    isCancelable = true // 终态了，back / 点外部也能收起
                    flushLog()
                }
                CancelMode.COOPERATIVE -> {
                    // 不 cancel job —— producer 会自己收尾(写截短文件 / emit Done)
                    stopRequested = true
                    cancelBtn.isEnabled = false
                    cancelBtn.text = "stopping…"
                    appendLine(getString(config.stopRequestedLineRes))
                    flushLog()
                }
            }
        }
        closeBtn.setOnClickListener { dismissAllowingStateLoss() }
        // 会话模式下任务在管理器里跑，收起窗口只是"看不见"，不是"停掉"：
        // 运行中就给出显式出口，别让用户只能靠返回键（而且以前返回键还被 keepOpenUntilDone 锁着）。
        if (config.dismissibleWhileRunning) {
            closeBtn.text = getString(config.backgroundLabelRes)
            closeBtn.visibility = View.VISIBLE
        }
        openManagerBtn.setOnClickListener {
            val ctx = requireContext()
            val intent = Intent(ctx, TemplateActivity::class.java)
                .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DOWNLOAD_MANAGER.key) // route key, not UI text
            ctx.startActivity(intent)
            dismissAllowingStateLoss()
        }

        startedAt = System.currentTimeMillis()

        val bound = session
        if (bound != null) {
            // 会话的 producer 在进程级 scope 里跑，这里只订阅：本 dialog 销毁 / 页面退出
            // 都不影响下载，重新打开还能靠 replay 把历史日志重建出来。
            // attach/detach 让管理器知道"收尾时有没有人在看"，没人看就补一条 toast。
            bound.attach()
            fetchJob = bound.events
                .onEach(::handleEvent)
                .launchIn(viewLifecycleOwner.lifecycleScope)
        } else flow?.let { f ->
            // 收集 fetch 事件 —— activity scope，dialog 关掉也继续
            fetchJob = f.flowOn(Dispatchers.IO)
                .onEach(::handleEvent)
                // producer 里没接住的异常(最典型:写盘时 MediaStore 目录被占用抛 ISE)
                // 以前直接崩进 Crashlytics。这里兜底,收成 FAILED 终态 + 友好 QMUI 弹窗。
                .catch { onFlowFatal(it) }
                .launchIn(requireActivity().lifecycleScope)
        }

        // 100ms 状态行刷新（spinner + 倒计时 + elapsed）—— 让用户感觉一直在动。
        // 一旦进入终态（frozenElapsedMs != null）立刻停转 —— 抓取完成后不该还有跳动的计时器。
        viewLifecycleOwner.lifecycleScope.launch {
            while (viewAlive && frozenElapsedMs == null) {
                spinnerFrame = (spinnerFrame + 1) % SPINNER.size
                refreshStatusLine()
                delay(STATUS_TICK_MS)
            }
            // 终态最后刷一次，确保显示的就是冻结值
            if (viewAlive) refreshStatusLine()
        }
    }

    /**
     * 露出关闭按钮。终态时把「后台继续」换回「关闭」——任务已经结束，
     * 这时按钮的语义就只是收窗口，不该再写着"继续"。
     */
    private fun showCloseButton(terminal: Boolean) {
        if (terminal && config.dismissibleWhileRunning) {
            closeBtn.text = getString(R.string.bulk_fetch_dialog_btn_close)
        }
        closeBtn.visibility = View.VISIBLE
    }

    private fun handleEvent(e: FetchEvent) {
        when (e) {
            is FetchEvent.Started -> {
                if (viewAlive) {
                    titleView.text = "${config.title} · ${e.subtitle}"
                    appendLine("> ${e.taskName}")
                    appendLine("  source=${e.subtitle}, streaming pages…")
                    flushLog()
                }
            }
            is FetchEvent.Networking -> {
                phase = Phase.NETWORKING
                phaseDetail = "GET ${e.endpoint}"
                pageIndex = e.pageIndex
                if (viewAlive) {
                    appendLine("> ${config.stepNoun} ${e.pageIndex}: GET ${e.endpoint}")
                    flushLog()
                }
            }
            is FetchEvent.PageReceived -> {
                phase = Phase.RECEIVED
                phaseDetail = "received ${e.pageSize} ${config.itemNoun} in ${e.latencyMs}ms"
                if (viewAlive) {
                    appendLine("  ↳ received ${e.pageSize} ${config.itemNoun} in ${e.latencyMs}ms")
                    flushLog()
                }
            }
            is FetchEvent.DbBatchStart -> {
                phase = Phase.DB
                phaseDetail = "writing ${e.size} rows to download_queue"
            }
            is FetchEvent.DbBatchDone -> {
                phase = Phase.ENQUEUED
                phaseDetail = "db inserted ${e.size} rows in ${e.latencyMs}ms"
                if (viewAlive) {
                    appendLine("  ↳ db: inserted ${e.size} rows in ${e.latencyMs}ms")
                }
            }
            is FetchEvent.Enqueued -> {
                totalSoFar = e.totalSoFar
                if (viewAlive) {
                    appendLine("  ↳ enqueued ✓  cumulative=${e.totalSoFar}")
                    flushLog()
                }
            }
            is FetchEvent.RateLimit -> {
                phase = Phase.RATE_LIMIT
                rateLimitUntil = System.currentTimeMillis() + e.waitMs
                phaseDetail = "rate-limit"
                if (viewAlive) {
                    appendLine("  ⏳ rate-limit: sleep ${e.waitMs}ms")
                    flushLog()
                }
            }
            is FetchEvent.ChapterMerged -> {
                phase = Phase.MERGING_CHAPTER
                totalSoFar = e.chapterIndex
                pageIndex = e.chapterIndex
                phaseDetail = "ch ${e.chapterIndex}/${e.totalChapters} · ${e.latencyMs}ms"
                if (viewAlive) {
                    val shortTitle = e.title.take(40)
                    appendLine("  ↳ ch ${e.chapterIndex}/${e.totalChapters}: $shortTitle  (${e.latencyMs}ms)")
                    flushLog()
                }
            }
            is FetchEvent.ImageFetched -> {
                phase = Phase.FETCHING_IMAGE
                phaseDetail = "img ${e.key} · ${e.bytes / 1024}KB"
                if (viewAlive) {
                    appendLine("  ↳ img ${e.key}: ${e.bytes / 1024}KB")
                    flushLog()
                }
            }
            is FetchEvent.WritingFile -> {
                phase = Phase.WRITING_FILE
                phaseDetail = "writing ${e.filename}"
                if (viewAlive) {
                    appendLine("> writing ${e.filename}…")
                    flushLog()
                }
            }
            is FetchEvent.Log -> {
                if (viewAlive) {
                    appendLine(e.line)
                    flushLog()
                }
            }
            is FetchEvent.Warning -> {
                if (viewAlive) {
                    appendLine("  ⚠ ${e.message}")
                    flushLog()
                }
            }
            is FetchEvent.Done -> {
                phase = Phase.DONE
                phaseDetail = "completed"
                totalSoFar = e.total
                // 用 fetcher 上报的精确 elapsed，避免和 dialog 本地的 startedAt 漂移
                frozenElapsedMs = e.elapsedMs
                if (viewAlive) {
                    appendLine("")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine(getString(config.doneTitleRes))
                    appendLine("")
                    appendLine(getString(config.doneTotalRes, e.total))
                    appendLine(getString(config.donePagesRes, e.pageCount, formatDuration(e.elapsedMs)))
                    config.doneExtraRes.forEach { appendLine(getString(it)) }
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    cancelBtn.visibility = View.GONE
                    if (config.showOpenManager) openManagerBtn.visibility = View.VISIBLE
                    showCloseButton(terminal = true)
                    isCancelable = true // 终态了，back / 点外部也能收起
                    flushLog()
                }
            }
            is FetchEvent.Errored -> {
                phase = Phase.FAILED
                phaseDetail = "${config.stepNoun} ${e.pageIndex} failed"
                freezeTimer()
                if (viewAlive) {
                    appendLine("")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine(getString(config.failedTitleRes, e.pageIndex))
                    appendLine(getString(config.failedMessageRes, e.message))
                    // 0 时不出「已合并的 0 章丢失」这种废话行 —— batch illust 路径
                    // 也顺带受益(以前 totalSoFar=0 时也会出「已入队 0 项」)。
                    if (totalSoFar > 0) {
                        appendLine(getString(config.failedPartialRes, totalSoFar))
                    }
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    cancelBtn.visibility = View.GONE
                    if (config.showOpenManager && totalSoFar > 0) openManagerBtn.visibility = View.VISIBLE
                    showCloseButton(terminal = true)
                    isCancelable = true // 终态了，back / 点外部也能收起
                    flushLog()
                }
            }
        }
    }

    /**
     * Flow producer 抛出的、没在 producer 内部转成 [FetchEvent.Errored] 的异常的兜底。
     * 最典型的是写盘时 [ceui.pixiv.download.backend.MediaStoreBackend] 因目录被系统/
     * 别的 app 占用而 throw 的 IllegalStateException(消息本身就是可操作的中文提示)。
     * 以前没有 .catch 直接崩进 Crashlytics(20 events / 4 users)。现在:
     *   1) 把进度 dialog 推进 FAILED 终态 —— 不再卡在转圈、补出 close 按钮;
     *   2) 弹一个友好的 QMUI 弹窗,把可操作提示直接糊到用户脸上(CLI 日志区太容易被忽略)。
     */
    private fun onFlowFatal(e: Throwable) {
        if (e is CancellationException) throw e
        Timber.e(e, "FetchProgressDialog flow crashed")
        handleEvent(FetchEvent.Errored(e.message ?: "", totalSoFar))
        val act = activity ?: return
        val message = e.message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.dlmgr_active_size_failed)
        WitDialog.MessageDialogBuilder(act)
            .setTitle(R.string.string_143)
            .setMessage(message)
            .addAction(R.string.sure) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * 双行固定结构的状态显示（避免 wrap_content 多行带来的 dialog 高度跳变）：
     *   行 1（statusLine）: "{spinner} {action}"  —— singleLine + ellipsize
     *   行 2（statusMetrics）: "step=N · total=M · elapsed=X · R/s"
     */
    private fun freezeTimer() {
        if (frozenElapsedMs == null) {
            frozenElapsedMs = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt
        }
    }

    private fun refreshStatusLine() {
        if (!viewAlive) return
        val now = System.currentTimeMillis()
        val elapsedMs = frozenElapsedMs
            ?: if (startedAt == 0L) 0L else now - startedAt
        val spin = SPINNER[spinnerFrame]
        val stopHint = if (stopRequested && phase !in TERMINAL_PHASES) " · stopping…" else ""

        val (icon, action) = when (phase) {
            Phase.IDLE -> spin to "starting…"
            Phase.NETWORKING -> spin to "fetching ${config.stepNoun} $pageIndex · $phaseDetail$stopHint"
            Phase.RECEIVED -> spin to "${config.stepNoun} $pageIndex received · $phaseDetail$stopHint"
            Phase.DB -> spin to "writing db · $phaseDetail$stopHint"
            Phase.ENQUEUED -> spin to "${config.stepNoun} $pageIndex enqueued$stopHint"
            Phase.RATE_LIMIT -> {
                val left = (rateLimitUntil - now).coerceAtLeast(0L)
                if (left > 0) "⏳" to getString(R.string.bulk_fetch_dialog_rate_limit, left / 1000.0)
                else spin to "fetching next ${config.stepNoun}…"
            }
            Phase.MERGING_CHAPTER -> spin to "merging · $phaseDetail$stopHint"
            Phase.FETCHING_IMAGE -> spin to "fetching image · $phaseDetail$stopHint"
            Phase.WRITING_FILE -> spin to "writing file · $phaseDetail"
            Phase.DONE -> "✓" to "completed · ${totalSoFar} ${config.itemNoun} ${config.completedVerb}"
            Phase.FAILED -> "✗" to "failed at ${config.stepNoun} $pageIndex"
            Phase.CANCELED -> "●" to "canceled · ${totalSoFar} ${config.itemNoun} ${config.canceledVerb}"
        }

        // 速率：每秒入队多少 item
        val rate = if (elapsedMs > 1000 && totalSoFar > 0) {
            String.format(Locale.US, "%.1f/s", totalSoFar * 1000.0 / elapsedMs)
        } else "—"

        statusLine.text = "$icon $action"
        statusMetrics.text = buildString {
            append(config.stepNoun).append("=").append(if (pageIndex == 0) "—" else pageIndex.toString())
            append(" · total=").append(totalSoFar)
            append(" · elapsed=").append(formatDuration(elapsedMs))
            append(" · ").append(rate)
        }
    }

    private fun appendLine(line: String) {
        val ts = timeFmt.format(Date())
        ringBuffer.addLast("[$ts] $line")
        while (ringBuffer.size > MAX_LINES) ringBuffer.removeFirst()
    }

    private fun flushLog() {
        if (!viewAlive) return
        val sb = StringBuilder(ringBuffer.size * 60)
        for (line in ringBuffer) sb.append(line).append('\n')
        logText.text = sb
        logScroll.post { if (viewAlive) logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> String.format("%dm%02ds", s / 60, s % 60)
            else -> String.format("%dh%02dm%02ds", s / 3600, (s % 3600) / 60, s % 60)
        }
    }

    override fun onDestroyView() {
        viewAlive = false
        // 会话模式：告诉管理器"已经没人在看了"，它好在收尾时补 toast。
        session?.detach()
        super.onDestroyView()
        // flow 模式故意不取消 fetchJob —— activity scope 让抓取在 dialog 关掉后继续；
        // 会话模式的订阅挂在 viewLifecycleOwner 上，随 view 一起停，但生产者在管理器里，照跑。
    }

    // ────────────────────────────────────────────────────────────────────
    // Config + 默认工厂
    // ────────────────────────────────────────────────────────────────────

    enum class CancelMode {
        /** 立刻 cancel job,UI 进 CANCELED 终态(默认,batch illust 走这条) */
        HARD,
        /** 不 cancel job,只标 "stopping…",让 producer 自己优雅收尾(merge 走这条) */
        COOPERATIVE,
    }

    data class Config(
        /** 顶部 fake-terminal 标题 */
        val title: String = "batch-download",
        /** 开场提示符行 */
        val headerCmd: String = "\$ fetch-author-works --stream --verbose",
        /** 是否显示「去下载管理」按钮(到 Done/Errored 终态时) */
        val showOpenManager: Boolean = true,
        /** status line / 终态总结里用作单位的名词,例 "items" / "chapters" */
        val itemNoun: String = "items",
        /** status line / 进度行的步骤名词,例 "page" / "ch" */
        val stepNoun: String = "page",
        /** "completed · N items queued" 里的动词 */
        val completedVerb: String = "queued",
        /** "canceled · N items kept" 里的动词 */
        val canceledVerb: String = "kept",
        @StringRes val closeHintRes: Int = R.string.bulk_fetch_dialog_close_hint,
        @StringRes val canceledLineRes: Int = R.string.bulk_fetch_dialog_canceled,
        @StringRes val stopRequestedLineRes: Int = R.string.bulk_fetch_dialog_canceled,
        @StringRes val doneTitleRes: Int = R.string.bulk_fetch_dialog_done_title,
        @StringRes val doneTotalRes: Int = R.string.bulk_fetch_dialog_done_total,
        @StringRes val donePagesRes: Int = R.string.bulk_fetch_dialog_done_pages,
        val doneExtraRes: List<Int> = listOf(
            R.string.bulk_fetch_dialog_done_queue,
            R.string.bulk_fetch_dialog_done_started,
        ),
        @StringRes val failedTitleRes: Int = R.string.bulk_fetch_dialog_failed_title,
        @StringRes val failedMessageRes: Int = R.string.bulk_fetch_dialog_failed_message,
        @StringRes val failedPartialRes: Int = R.string.bulk_fetch_dialog_failed_partial,
        /** cancel 按钮按下时的行为 */
        val cancelMode: CancelMode = CancelMode.HARD,
        /**
         * true = 任务跑完（或用户显式「取消」收尾）前禁止 back / 点外部收起 dialog。
         * 给那些把全部活儿都跑在这条 flow 里、没有持久化队列兜底的任务用（merge novel：
         * 只在最后一刻写盘，窗口一关 + 离开页面就整单丢失，issue #919）。默认 false：
         * batch illust 已入队，关掉安全。
         */
        val keepOpenUntilDone: Boolean = false,
        /**
         * true = 任务跑在进程级会话里，收起窗口不影响它，所以**运行中就**露出一个
         * 「后台继续」按钮（默认的 closeBtn 只在终态出现）。小说合并下载用它；
         * 插画批量虽然也入的是持久化队列，但它的窗口本来就可关，不需要这个按钮。
         */
        val dismissibleWhileRunning: Boolean = false,
        /** 运行中那个收起按钮的文案。 */
        @StringRes val backgroundLabelRes: Int = R.string.merge_novel_dialog_btn_background,
        /** cancel 按钮按下时无论 cancelMode 都会触发的钩子 */
        val onCancelRequested: () -> Unit = {},
    )

    companion object {
        private const val MAX_LINES = 200
        private const val STATUS_TICK_MS = 100L

        // Braille spinner — 比 / - \ | 平滑得多
        private val SPINNER = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

        private val TERMINAL_PHASES = setOf(Phase.DONE, Phase.FAILED, Phase.CANCELED)

        /** 旧 API:不传 config = 走 batch illust 默认。BulkActions 那条路保持不动。 */
        fun show(fm: FragmentManager, flow: Flow<FetchEvent>): FetchProgressDialog {
            val dialog = FetchProgressDialog().apply { bindFlow(flow) }
            dialog.show(fm, "FetchProgressDialog")
            return dialog
        }

        /** 新 API:带 config,merge novel series 等场景用。 */
        fun show(fm: FragmentManager, flow: Flow<FetchEvent>, config: Config): FetchProgressDialog {
            val dialog = FetchProgressDialog().apply {
                bindFlow(flow)
                bindConfig(config)
            }
            dialog.show(fm, "FetchProgressDialog")
            return dialog
        }

        /**
         * 小说合并下载：绑 [NovelMergeSession]（进程级生产者的订阅端）。
         * 弹窗被关掉 / 宿主页面退出都不会取消下载。
         */
        fun show(fm: FragmentManager, session: NovelMergeSession, config: Config): FetchProgressDialog {
            val dialog = FetchProgressDialog().apply {
                bindSession(session)
                bindConfig(config)
            }
            dialog.show(fm, "FetchProgressDialog")
            return dialog
        }
    }
}
