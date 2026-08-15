package ceui.pixiv.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.http.ImageHostManager
import ceui.lisa.utils.Common
import ceui.loxia.hideKeyboard
import com.blankj.utilcode.util.BarUtils
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import kotlinx.coroutines.launch

/**
 * 网络测试页 —— 纯渲染 + 转发点击，所有测试逻辑与状态在 [NetworkTestViewModel]。
 * 见 [fragment_network_perf_test] 布局；目标卡 / 步骤行用 item_network_test_target /
 * item_network_test_step 动态 inflate。颜色全部取自 v3_* 资源（带 values-night），
 * 状态 pill / 圆点用 [pillBackground] 按状态染色，白天黑夜自动适配。
 */
class NetworkTestFragment : Fragment(R.layout.fragment_network_perf_test) {

    private val viewModel by viewModels<NetworkTestViewModel>()

    private lateinit var chipDoh: TextView
    private lateinit var chipDirect: TextView
    private lateinit var chipHost: TextView
    private lateinit var summaryCard: View
    private lateinit var summaryPill: TextView
    private lateinit var summaryPillSlow: TextView
    private lateinit var summaryPillDim: TextView
    private lateinit var summaryPillDegraded: TextView
    private lateinit var summaryPillBypass: TextView
    private lateinit var summarySub: TextView
    private lateinit var emptyState: View
    private lateinit var resultsSection: View
    private lateinit var resultsContainer: LinearLayout
    private lateinit var illustInput: EditText
    private lateinit var btnIllustTest: TextView
    private lateinit var illustResultContainer: LinearLayout
    private lateinit var imageDownloadContainer: LinearLayout
    private lateinit var rawLogText: TextView
    private lateinit var btnRawLogToggle: TextView
    private lateinit var btnRun: TextView
    private lateinit var btnCopy: TextView

    private var rawLogShown = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar).apply {
            // EdgeToEdge host: pad the status bar at runtime instead of fitsSystemWindows.
            updatePadding(top = BarUtils.getStatusBarHeight())
            setNavigationOnClickListener { activity?.finish() }
        }

        // 固定底栏垫上导航栏 inset，避免主按钮被系统栏遮住（12dp 基线 + 导航栏高度）。
        val bottomBar = view.findViewById<View>(R.id.bottom_bar)
        val basePaddingBottom = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = basePaddingBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(bottomBar)

        chipDoh = view.findViewById(R.id.chip_doh)
        chipDirect = view.findViewById(R.id.chip_direct)
        chipHost = view.findViewById(R.id.chip_host)
        summaryCard = view.findViewById(R.id.summary_card)
        summaryPill = view.findViewById(R.id.summary_pill)
        summaryPillSlow = view.findViewById(R.id.summary_pill_slow)
        summaryPillDim = view.findViewById(R.id.summary_pill_dim)
        summaryPillDegraded = view.findViewById(R.id.summary_pill_degraded)
        summaryPillBypass = view.findViewById(R.id.summary_pill_bypass)
        summarySub = view.findViewById(R.id.summary_sub)
        emptyState = view.findViewById(R.id.empty_state)
        resultsSection = view.findViewById(R.id.results_section)
        resultsContainer = view.findViewById(R.id.results_container)
        illustInput = view.findViewById(R.id.illust_id_input)
        btnIllustTest = view.findViewById(R.id.btn_illust_test)
        illustResultContainer = view.findViewById(R.id.illust_result_container)
        imageDownloadContainer = view.findViewById(R.id.image_download_result_container)
        rawLogText = view.findViewById(R.id.raw_log_text)
        btnRawLogToggle = view.findViewById(R.id.btn_rawlog_toggle)
        btnRun = view.findViewById(R.id.btn_run)
        btnCopy = view.findViewById(R.id.btn_copy)

        renderEnvChips()

        btnRun.setOnClickListener {
            hideKeyboard()
            viewModel.runTests()
        }
        btnIllustTest.setOnClickListener {
            hideKeyboard()
            val id = illustInput.text?.toString()?.trim()?.toLongOrNull()
            if (id == null || id <= 0) {
                Common.showToast(getString(R.string.network_test_illust_invalid_id))
            } else {
                viewModel.probeIllust(id)
            }
        }
        btnCopy.setOnClickListener { copyLog() }
        btnRawLogToggle.setOnClickListener {
            rawLogShown = !rawLogShown
            rawLogText.visibility = if (rawLogShown) View.VISIBLE else View.GONE
            btnRawLogToggle.setText(
                if (rawLogShown) R.string.network_test_rawlog_hide else R.string.network_test_rawlog_show,
            )
            if (rawLogShown) rawLogText.text = viewModel.rawLog.value.orEmpty()
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.running.observe(viewLifecycleOwner) { isRunning ->
            btnRun.isEnabled = !isRunning
            btnRun.alpha = if (isRunning) 0.6f else 1f
            btnRun.setText(if (isRunning) R.string.network_test_running else R.string.network_test_run)
            if (isRunning) {
                emptyState.visibility = View.GONE
                resultsSection.visibility = View.VISIBLE
                renderEnvChips()
            }
        }
        viewModel.targets.observe(viewLifecycleOwner) { list ->
            if (list.isEmpty()) {
                resultsContainer.removeAllViews()
                return@observe
            }
            resultsSection.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            renderTargetList(resultsContainer, list)
        }
        viewModel.overall.observe(viewLifecycleOwner) { renderSummary(it) }
        viewModel.overallSub.observe(viewLifecycleOwner) { renderSummary(viewModel.overall.value) }
        viewModel.imageDownloadSlow.observe(viewLifecycleOwner) { renderSummary(viewModel.overall.value) }
        viewModel.imageDimensionFailed.observe(viewLifecycleOwner) { renderSummary(viewModel.overall.value) }
        viewModel.pollutionBypassed.observe(viewLifecycleOwner) { renderSummary(viewModel.overall.value) }
        // 日志默认收起：隐藏时不做 TextView 刷新（大文本逐行重建是渐进掉帧来源之一），展开时再同步。
        viewModel.rawLog.observe(viewLifecycleOwner) {
            if (rawLogShown) rawLogText.text = it
        }
        viewModel.illustRunning.observe(viewLifecycleOwner) { isRunning ->
            btnIllustTest.isEnabled = !isRunning
            btnIllustTest.alpha = if (isRunning) 0.6f else 1f
        }
        viewModel.illustReport.observe(viewLifecycleOwner) { report ->
            renderTargetList(illustResultContainer, if (report != null) listOf(report) else emptyList())
        }
        viewModel.imageDownloadReport.observe(viewLifecycleOwner) { report ->
            renderTargetList(imageDownloadContainer, if (report != null) listOf(report) else emptyList())
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pollutionAlert.collect { showPollutionDialog(it) }
            }
        }
    }

    private fun renderEnvChips() {
        if (viewModel.dohEnabled) {
            applyPill(chipDoh, getString(R.string.network_test_env_doh_on), R.color.v3_green)
        } else {
            applyPill(chipDoh, getString(R.string.network_test_env_doh_off), R.color.v3_text_3)
        }
        if (viewModel.directConnect) {
            applyPill(chipDirect, getString(R.string.network_test_env_direct_on), R.color.v3_green)
        } else {
            applyPill(chipDirect, getString(R.string.network_test_env_direct_off), R.color.v3_text_3)
        }
        applyPill(chipHost, getString(R.string.network_test_env_host_prefix) + imageHostLabel(), R.color.v3_blue)
    }

    private fun imageHostLabel(): String = when (ImageHostManager.getMode()) {
        ImageHostManager.Mode.PIXIV -> getString(R.string.image_host_pixiv_official)
        ImageHostManager.Mode.PIXIV_CAT -> getString(R.string.image_host_pixiv_cat)
        ImageHostManager.Mode.PIXIV_RE -> getString(R.string.image_host_pixiv_re)
        ImageHostManager.Mode.PIXIV_NL -> getString(R.string.image_host_pixiv_nl)
        ImageHostManager.Mode.CUSTOM -> {
            val host = ImageHostManager.getCustomHost()
            if (host.isEmpty()) getString(R.string.image_host_custom) else host
        }
    }

    private fun renderSummary(overall: OverallStatus?) {
        if (overall == null) {
            summaryCard.visibility = View.GONE
            return
        }
        summaryCard.visibility = View.VISIBLE
        // DNS 污染但绕过生效：黄底污染 pill 旁并列绿底「网络勉强可用」。
        val bypassed = viewModel.pollutionBypassed.value == true
        summaryPillBypass.visibility = if (bypassed) View.VISIBLE else View.GONE
        if (bypassed) applyPill(summaryPillBypass, getString(R.string.network_test_overall_bypass_ok), R.color.v3_green)
        // 下载缓慢与主状态并列显示（黄底）。
        val slow = viewModel.imageDownloadSlow.value == true
        summaryPillSlow.visibility = if (slow) View.VISIBLE else View.GONE
        if (slow) applyPill(summaryPillSlow, getString(R.string.network_test_slow_download), R.color.v3_gold)
        // 图片尺寸探测失败：与主状态并列显示（黄底）。
        val dimFailed = viewModel.imageDimensionFailed.value == true
        summaryPillDim.visibility = if (dimFailed) View.VISIBLE else View.GONE
        if (dimFailed) applyPill(summaryPillDim, getString(R.string.network_test_dim_probe_failed_top), R.color.v3_gold)
        // 污染但绕过未生效（污染域握手失败）：红底污染 pill 旁并列橙底「部分异常」，
        // 小字说明连通性/握手问题；绕过生效时上面已并列「网络勉强可用」。
        val bypassFailed = overall == OverallStatus.POLLUTED && !bypassed
        summaryPillDegraded.visibility = if (bypassFailed) View.VISIBLE else View.GONE
        if (bypassFailed) applyPill(summaryPillDegraded, getString(R.string.network_test_overall_degraded), R.color.v3_orange)
        when (overall) {
            OverallStatus.CLEAN -> {
                applyPill(summaryPill, "● " + getString(R.string.network_test_overall_clean), R.color.v3_green)
                summarySub.text = viewModel.overallSub.value
                    ?: getString(R.string.network_test_overall_clean_sub)
            }
            OverallStatus.HIGH_LATENCY -> {
                applyPill(summaryPill, "● " + getString(R.string.network_test_overall_high_latency), R.color.v3_orange)
                summarySub.text = viewModel.overallSub.value
                    ?: getString(R.string.network_test_overall_high_latency_sub)
            }
            OverallStatus.EXTREME_LATENCY -> {
                applyPill(summaryPill, "● " + getString(R.string.network_test_overall_extreme_latency), R.color.v3_danger)
                summarySub.text = viewModel.overallSub.value
                    ?: getString(R.string.network_test_overall_high_latency_sub)
            }
            OverallStatus.DEGRADED -> {
                applyPill(summaryPill, "● " + getString(R.string.network_test_overall_degraded), R.color.v3_orange)
                // 异常终止时 ViewModel 会 post aborted_sub，优先展示它而不是固定文案
                summarySub.text = viewModel.overallSub.value
                    ?: getString(R.string.network_test_overall_degraded_sub)
            }
            OverallStatus.POLLUTED -> {
                if (bypassed) {
                    applyPill(summaryPill, "● " + getString(R.string.network_test_overall_polluted), R.color.v3_gold)
                    summarySub.setText(R.string.network_test_overall_polluted_bypass_sub)
                } else {
                    // 污染且绕过未生效：污染 pill 旁已并列「部分异常」，小字指向连通性/握手问题
                    applyPill(summaryPill, "● " + getString(R.string.network_test_overall_polluted), R.color.v3_danger)
                    summarySub.setText(R.string.network_test_overall_degraded_sub)
                }
            }
        }
    }

    /**
     * 原地复用 [container] 里的卡片 / 步骤行，只更新有变化的条目。
     * 之前每次 LiveData 通知都 removeAllViews + 全量 inflate，测试过程中步骤逐条追加、
     * 握手反复刷新，重建成本随内容增长，是「越测试越掉帧」的主因。
     */
    private fun renderTargetList(container: LinearLayout, reports: List<TargetReport>) {
        var i = 0
        while (i < reports.size) {
            val card = if (i < container.childCount) container.getChildAt(i) else null
            updateTargetCard(card, reports[i], container)
            i++
        }
        while (container.childCount > reports.size) {
            container.removeViewAt(container.childCount - 1)
        }
    }

    private fun updateTargetCard(existing: View?, report: TargetReport, parent: ViewGroup): View {
        val card = existing ?: layoutInflater.inflate(R.layout.item_network_test_target, parent, false)
        val title = card.findViewById<TextView>(R.id.target_title)
        if (title.text != report.title) title.text = report.title
        val subtitle = card.findViewById<TextView>(R.id.target_subtitle)
        if (subtitle.text != report.subtitle) subtitle.text = report.subtitle

        val (label, colorRes) = targetStatusStyle(report.status)
        applyPill(card.findViewById(R.id.target_status_pill), label, colorRes)
        // 可选并列 pill（如图片尺寸探测失败），黄底。
        val extraPill = card.findViewById<TextView>(R.id.target_status_pill_extra)
        if (report.extraPill.isNullOrBlank()) {
            extraPill.visibility = View.GONE
        } else {
            extraPill.visibility = View.VISIBLE
            applyPill(extraPill, report.extraPill, R.color.v3_gold)
        }

        val steps = card.findViewById<LinearLayout>(R.id.steps_container)
        var j = 0
        while (j < report.steps.size) {
            val row = if (j < steps.childCount) steps.getChildAt(j) else null
            updateStepRow(row, report.steps[j], steps)
            j++
        }
        while (steps.childCount > report.steps.size) {
            steps.removeViewAt(steps.childCount - 1)
        }
        if (card.parent == null) parent.addView(card)
        return card
    }

    private fun updateStepRow(existing: View?, step: TestStep, parent: ViewGroup): View {
        val row = existing ?: layoutInflater.inflate(R.layout.item_network_test_step, parent, false)
        val icon = row.findViewById<TextView>(R.id.step_icon)
        val iconText = stepIcon(step.status)
        if (icon.text.toString() != iconText) icon.text = iconText
        val color = ContextCompat.getColor(requireContext(), stepColorRes(step.status))
        if (icon.currentTextColor != color) icon.setTextColor(color)

        val label = row.findViewById<TextView>(R.id.step_label)
        if (label.text.toString() != step.label) label.text = step.label
        val detail = row.findViewById<TextView>(R.id.step_detail)
        if (step.detail.isNullOrBlank()) {
            if (detail.visibility != View.GONE) detail.visibility = View.GONE
        } else {
            if (detail.visibility != View.VISIBLE) detail.visibility = View.VISIBLE
            if (detail.text.toString() != step.detail) detail.text = step.detail
        }
        // 新行 inflate 后必须挂到容器，否则变成孤儿 View，步骤永远不显示。
        if (row.parent == null) parent.addView(row)
        return row
    }

    private fun targetStatusStyle(status: TargetStatus): Pair<String, Int> = when (status) {
        TargetStatus.RUNNING -> "测试中" to R.color.v3_blue
        TargetStatus.OK -> "通畅" to R.color.v3_green
        TargetStatus.HIGH_LATENCY -> "高延迟" to R.color.v3_orange
        TargetStatus.EXTREME_LATENCY -> "超高延迟" to R.color.v3_danger
        TargetStatus.DEGRADED -> "部分异常" to R.color.v3_orange
        TargetStatus.POLLUTED -> "DNS 污染" to R.color.v3_danger
        TargetStatus.POLLUTED_BYPASSED -> "已绕过" to R.color.v3_gold
        TargetStatus.FAILED -> "失败" to R.color.v3_danger
    }

    private fun stepIcon(status: StepStatus): String = when (status) {
        StepStatus.OK -> "✓"
        StepStatus.WARN -> "⚠"
        StepStatus.FAIL -> "✗"
        StepStatus.RUNNING -> "…"
        StepStatus.INFO -> "•"
        StepStatus.HIGH_LATENCY -> "⚠"
        StepStatus.EXTREME_LATENCY -> "⚠"
    }

    private fun stepColorRes(status: StepStatus): Int = when (status) {
        StepStatus.OK -> R.color.v3_green
        StepStatus.WARN -> R.color.v3_orange
        StepStatus.FAIL -> R.color.v3_danger
        StepStatus.RUNNING -> R.color.v3_blue
        StepStatus.INFO -> R.color.v3_text_3
        StepStatus.HIGH_LATENCY -> R.color.v3_orange
        StepStatus.EXTREME_LATENCY -> R.color.v3_danger
    }

    /** 圆角 pill：同色 20% 填充 + 同色文字，over v3 玻璃卡，明暗模式都清晰。 */
    private fun applyPill(view: TextView, text: String, colorRes: Int) {
        val color = ContextCompat.getColor(requireContext(), colorRes)
        view.text = text
        view.setTextColor(color)
        view.background = GradientDrawable().apply {
            cornerRadius = 40f * resources.displayMetrics.density
            setColor(ColorUtils.setAlphaComponent(color, 0x33))
        }
    }

    private fun copyLog() {
        val text = viewModel.rawLog.value.orEmpty()
        if (text.isBlank()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("network-test", text))
        Common.showToast(getString(R.string.network_test_log_copied))
    }

    private fun showPollutionDialog(alert: NetworkAlert) {
        val act = activity ?: return
        QMUIDialog.MessageDialogBuilder(act)
            .setTitle(alert.titleRes)
            .setMessage(alert.message)
            .setSkinManager(QMUISkinManager.defaultInstance(act))
            .addAction(R.string.network_test_pollution_dialog_action) { d, _ -> d.dismiss() }
            .show()
    }
}
