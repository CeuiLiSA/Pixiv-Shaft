package ceui.pixiv.ui.bulk

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import ceui.lisa.activities.TemplateActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Claude Code CLI 风格的进度 dialog —— 用户能 **一直** 看到 "当前在干嘛"，绝不让人干等。
 *
 * 关键体感：
 *   - 顶部状态行 100ms 一刷，包含：
 *       {spinner 帧} {当前操作描述}  ·  page=N/total  ·  elapsed  ·  rate
 *   - 网络/DB/池/速率限制 都有独立子文案
 *   - rate-limit 实时倒计时 "1.20s 后继续"
 *   - 日志区记录每一个微动作（page received / latency / db ms / pool ms / cumulative）
 */
class FetchProgressDialog : DialogFragment(R.layout.dialog_fetch_progress) {

    private var flow: Flow<FetchEvent>? = null
    private var fetchJob: Job? = null

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
    private enum class Phase { IDLE, NETWORKING, RECEIVED, POOL, DB, ENQUEUED, RATE_LIMIT, DONE, FAILED, CANCELED }
    @Volatile private var phase: Phase = Phase.IDLE
    @Volatile private var phaseDetail: String = ""
    @Volatile private var rateLimitUntil: Long = 0L
    @Volatile private var pageIndex: Int = 0
    @Volatile private var totalSoFar: Int = 0
    @Volatile private var startedAt: Long = 0L
    @Volatile private var spinnerFrame: Int = 0
    /** 进终态后冻结，refreshStatusLine 不再重新计算 elapsed，spinner ticker 也停止。 */
    @Volatile private var frozenElapsedMs: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 允许 back 收起 dialog —— fetch 在 activity scope 仍跑
        isCancelable = true
    }

    fun bindFlow(flow: Flow<FetchEvent>) {
        this.flow = flow
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

        titleView.text = "batch-download"
        statusLine.text = "${SPINNER[0]} starting…"
        statusMetrics.text = "page=— · total=0 · elapsed=0s · —"
        appendLine("$ fetch-author-works --stream --verbose")
        appendLine("  关掉此窗口不会停止抓取，可去 \"下载管理\" 查看进度")
        flushLog()

        cancelBtn.setOnClickListener {
            fetchJob?.cancel()
            phase = Phase.CANCELED
            phaseDetail = "user canceled"
            freezeTimer()
            appendLine("^C  user canceled — 已入队的项目保留，可在下载管理页继续/重试")
            cancelBtn.visibility = View.GONE
            closeBtn.visibility = View.VISIBLE
            flushLog()
        }
        closeBtn.setOnClickListener { dismissAllowingStateLoss() }
        openManagerBtn.setOnClickListener {
            val ctx = requireContext()
            val intent = Intent(ctx, TemplateActivity::class.java)
                .putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载管理")
            ctx.startActivity(intent)
            dismissAllowingStateLoss()
        }

        startedAt = System.currentTimeMillis()

        // 收集 fetch 事件 —— activity scope，dialog 关掉也继续
        flow?.let { f ->
            fetchJob = f.flowOn(Dispatchers.IO)
                .onEach(::handleEvent)
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

    private fun handleEvent(e: FetchEvent) {
        when (e) {
            is FetchEvent.Started -> {
                if (viewAlive) {
                    titleView.text = "batch-download · user:${e.userId}"
                    appendLine("> ${e.taskName}")
                    appendLine("  userId=${e.userId}, streaming pages…")
                    flushLog()
                }
            }
            is FetchEvent.Networking -> {
                phase = Phase.NETWORKING
                phaseDetail = "GET ${e.endpoint}"
                pageIndex = e.pageIndex
                if (viewAlive) {
                    appendLine("> page ${e.pageIndex}: GET ${e.endpoint}")
                    flushLog()
                }
            }
            is FetchEvent.PageReceived -> {
                phase = Phase.RECEIVED
                phaseDetail = "received ${e.pageSize} illusts in ${e.latencyMs}ms"
                if (viewAlive) {
                    appendLine("  ↳ received ${e.pageSize} illusts in ${e.latencyMs}ms")
                    flushLog()
                }
            }
            is FetchEvent.PoolUpdate -> {
                phase = Phase.POOL
                phaseDetail = "warming ObjectPool (${e.size} illusts)"
                if (viewAlive) {
                    appendLine("  ↳ pool: ${e.size} illusts updated")
                    // 不 flushLog —— 太频繁，统一节流到 100ms tick
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
            is FetchEvent.Done -> {
                phase = Phase.DONE
                phaseDetail = "completed"
                totalSoFar = e.total
                // 用 fetcher 上报的精确 elapsed，避免和 dialog 本地的 startedAt 漂移
                frozenElapsedMs = e.elapsedMs
                if (viewAlive) {
                    appendLine("")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("✓ 抓取完成")
                    appendLine("")
                    appendLine("  ▸ 总计: ${e.total} 项作品")
                    appendLine("  ▸ 共 ${e.pageCount} 页 · 耗时 ${formatDuration(e.elapsedMs)}")
                    appendLine("  ▸ 已加入下载队列，按入队顺序串行下载")
                    appendLine("  ▸ 下载已开始 →")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    cancelBtn.visibility = View.GONE
                    openManagerBtn.visibility = View.VISIBLE
                    closeBtn.visibility = View.VISIBLE
                    flushLog()
                }
            }
            is FetchEvent.Errored -> {
                phase = Phase.FAILED
                phaseDetail = "page ${e.pageIndex} failed"
                freezeTimer()
                if (viewAlive) {
                    appendLine("")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("✗ 抓取失败 · page ${e.pageIndex}")
                    appendLine("  ▸ ${e.message}")
                    appendLine("  ▸ 已入队 $totalSoFar 项已开始下载")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    cancelBtn.visibility = View.GONE
                    if (totalSoFar > 0) openManagerBtn.visibility = View.VISIBLE
                    closeBtn.visibility = View.VISIBLE
                    flushLog()
                }
            }
        }
    }

    /**
     * 双行固定结构的状态显示（避免 wrap_content 多行带来的 dialog 高度跳变）：
     *   行 1（statusLine）: "{spinner} {action}"  —— singleLine + ellipsize
     *   行 2（statusMetrics）: "page=N · total=M · elapsed=X · R/s"
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

        val (icon, action) = when (phase) {
            Phase.IDLE -> spin to "starting…"
            Phase.NETWORKING -> spin to "fetching page $pageIndex · $phaseDetail"
            Phase.RECEIVED -> spin to "page $pageIndex received · $phaseDetail"
            Phase.POOL -> spin to "warming pool · $phaseDetail"
            Phase.DB -> spin to "writing db · $phaseDetail"
            Phase.ENQUEUED -> spin to "page $pageIndex enqueued"
            Phase.RATE_LIMIT -> {
                val left = (rateLimitUntil - now).coerceAtLeast(0L)
                if (left > 0) "⏳" to String.format("rate-limit · %.2fs 后继续 (pixiv 速率限制)", left / 1000.0)
                else spin to "fetching next page…"
            }
            Phase.DONE -> "✓" to "completed · ${totalSoFar} items queued"
            Phase.FAILED -> "✗" to "failed at page $pageIndex"
            Phase.CANCELED -> "●" to "canceled · ${totalSoFar} items kept"
        }

        // 速率：每秒入队多少 item
        val rate = if (elapsedMs > 1000 && totalSoFar > 0) {
            String.format(Locale.US, "%.1f/s", totalSoFar * 1000.0 / elapsedMs)
        } else "—"

        statusLine.text = "$icon $action"
        statusMetrics.text = buildString {
            append("page=").append(if (pageIndex == 0) "—" else pageIndex.toString())
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
        super.onDestroyView()
        // 故意不取消 fetchJob —— activity scope 让抓取在 dialog 关掉后继续
    }

    companion object {
        private const val MAX_LINES = 200
        private const val STATUS_TICK_MS = 100L

        // Braille spinner — 比 / - \ | 平滑得多
        private val SPINNER = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

        fun show(fm: FragmentManager, flow: Flow<FetchEvent>): FetchProgressDialog {
            val dialog = FetchProgressDialog().apply { bindFlow(flow) }
            dialog.show(fm, "FetchProgressDialog")
            return dialog
        }
    }
}
