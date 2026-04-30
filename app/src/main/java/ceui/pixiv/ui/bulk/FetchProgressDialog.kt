package ceui.pixiv.ui.bulk

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * CLI 风格抓取进度 dialog。
 *
 * 性能/友好度要点：
 *  - 日志环形缓冲（[MAX_LINES] 行）：避免 StringBuilder 无界增长导致 setText O(n²)。
 *  - 事件分两类：
 *      * 终态事件（Done/Errored）即时刷新；
 *      * 高频事件（PageFetched/Enqueued/RateLimit）走 [MutableSharedFlow] + sample 节流，
 *        每 [FLUSH_INTERVAL_MS] 毫秒最多 1 次 UI 刷新；20000 作品 ~666 页，整体刷新 < 50 次。
 *  - 抓取在 activity 的 lifecycleScope 跑，dialog 关闭后 fetch 继续。
 *  - 允许 back 键收起 dialog（fetch 不取消），用户可去队列页查看。
 */
@OptIn(FlowPreview::class)
class FetchProgressDialog : DialogFragment(R.layout.dialog_fetch_progress) {

    private var flow: Flow<FetchEvent>? = null
    private var fetchJob: Job? = null

    private lateinit var titleView: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusLine: TextView
    private lateinit var cancelBtn: Button
    private lateinit var closeBtn: Button

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val ringBuffer = ArrayDeque<String>(MAX_LINES + 4)
    @Volatile private var viewAlive: Boolean = false

    // 当前进度状态（被 sample 后周期性 flush 到 UI）
    @Volatile private var lastPageIndex: Int = 0
    @Volatile private var lastTotal: Int = 0
    @Volatile private var lastEventTag: String = ""
    private val uiPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 允许 back 键收起 dialog —— fetch 在 activity scope 仍在跑
        isCancelable = true
    }

    fun bindFlow(flow: Flow<FetchEvent>) {
        this.flow = flow
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewAlive = true
        titleView = view.findViewById(R.id.title)
        logText = view.findViewById(R.id.logText)
        logScroll = view.findViewById(R.id.logScroll)
        statusLine = view.findViewById(R.id.statusLine)
        cancelBtn = view.findViewById(R.id.cancelBtn)
        closeBtn = view.findViewById(R.id.closeBtn)

        titleView.text = "batch-download"
        statusLine.text = "● running…    page=0  total=0"
        appendLine("$ fetch-author-works --stream")
        appendLine("  关掉此窗口不会停止抓取，可去 \"批量下载队列\" 查看进度")
        flushLog()

        cancelBtn.setOnClickListener {
            fetchJob?.cancel()
            appendLine("^C  user canceled — 已入队的项目保留，可在批量下载队列页继续/重试")
            statusLine.text = "● canceled"
            cancelBtn.visibility = View.GONE
            closeBtn.visibility = View.VISIBLE
            flushLog()
        }
        closeBtn.setOnClickListener { dismissAllowingStateLoss() }

        // 收集 fetch 事件 —— 在 activity scope，dialog 关掉也继续；UI handler 在 dialog 自己 scope
        flow?.let { f ->
            fetchJob = f.flowOn(Dispatchers.IO)
                .onEach(::handleEvent)
                .launchIn(requireActivity().lifecycleScope)
        }

        // UI 节流刷新：20000 作品下，原本每页 2 个事件 = 1300+ 次 setText；
        // 改为每 FLUSH_INTERVAL_MS 最多刷新一次，最多 ~50 次 setText。
        viewLifecycleOwner.lifecycleScope.launch {
            uiPulse
                .sample(FLUSH_INTERVAL_MS)
                .collect {
                    if (!viewAlive) return@collect
                    flushProgressLine()
                    flushLog()
                }
        }
    }

    private fun handleEvent(e: FetchEvent) {
        when (e) {
            is FetchEvent.Started -> {
                if (viewAlive) {
                    titleView.text = "batch-download · user:${e.userId}"
                    appendLine("> ${e.taskName}")
                    appendLine("> userId=${e.userId}, streaming pages…")
                    flushLog()
                }
            }
            is FetchEvent.PageFetched -> {
                lastPageIndex = e.pageIndex
                lastTotal = e.totalSoFar
                lastEventTag = "page +${e.pageSize}"
                // 节流：每 N 页才 append 一行日志，避免 666 行刷屏
                if (e.pageIndex % LOG_EVERY_N_PAGES == 0 || e.pageIndex == 1) {
                    appendLine("> page ${e.pageIndex}: +${e.pageSize}  (total ${e.totalSoFar})")
                }
                uiPulse.tryEmit(Unit)
            }
            is FetchEvent.Enqueued -> {
                lastTotal = e.totalSoFar
                uiPulse.tryEmit(Unit)
            }
            is FetchEvent.RateLimit -> {
                lastEventTag = "rate-limit"
                uiPulse.tryEmit(Unit)
            }
            is FetchEvent.Done -> {
                if (viewAlive) {
                    appendLine("✓ done. total=${e.total}  elapsed=${formatDuration(e.elapsedMs)}")
                    appendLine("  全部 ${e.total} 项已加入下载队列，按入队顺序串行下载")
                    statusLine.text = "● completed · ${e.total} items queued · ${formatDuration(e.elapsedMs)}"
                    cancelBtn.visibility = View.GONE
                    closeBtn.visibility = View.VISIBLE
                    flushLog()
                }
            }
            is FetchEvent.Errored -> {
                if (viewAlive) {
                    appendLine("✗ error: ${e.message}")
                    appendLine("  已入队的项目保留，可在批量下载队列页查看")
                    statusLine.text = "● failed · 已入队 $lastTotal 项"
                    cancelBtn.visibility = View.GONE
                    closeBtn.visibility = View.VISIBLE
                    flushLog()
                }
            }
        }
    }

    private fun flushProgressLine() {
        statusLine.text = "● running…    page=$lastPageIndex  total=$lastTotal  · $lastEventTag"
    }

    private fun appendLine(line: String) {
        val ts = timeFmt.format(Date())
        ringBuffer.addLast("[$ts] $line")
        while (ringBuffer.size > MAX_LINES) ringBuffer.removeFirst()
    }

    private fun flushLog() {
        if (!viewAlive) return
        // 一次性拼接 + 一次 setText，避免每行都 setText 整个文本
        val sb = StringBuilder(ringBuffer.size * 60)
        for (line in ringBuffer) sb.append(line).append('\n')
        logText.text = sb
        logScroll.post { if (viewAlive) logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "${s}s" else "${s / 60}m${s % 60}s"
    }

    override fun onDestroyView() {
        viewAlive = false
        super.onDestroyView()
        // 故意不取消 fetchJob —— activity scope 让抓取在 dialog 关掉后继续
    }

    companion object {
        private const val MAX_LINES = 100
        private const val FLUSH_INTERVAL_MS = 200L
        private const val LOG_EVERY_N_PAGES = 5

        fun show(fm: FragmentManager, flow: Flow<FetchEvent>): FetchProgressDialog {
            val dialog = FetchProgressDialog().apply { bindFlow(flow) }
            dialog.show(fm, "FetchProgressDialog")
            return dialog
        }
    }
}
