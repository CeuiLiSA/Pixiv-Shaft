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
    private lateinit var summaryCard: View
    private lateinit var summaryPill: TextView
    private lateinit var summarySub: TextView
    private lateinit var emptyState: View
    private lateinit var resultsSection: View
    private lateinit var resultsContainer: LinearLayout
    private lateinit var illustInput: EditText
    private lateinit var btnIllustTest: TextView
    private lateinit var illustResultContainer: LinearLayout
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
        summaryCard = view.findViewById(R.id.summary_card)
        summaryPill = view.findViewById(R.id.summary_pill)
        summarySub = view.findViewById(R.id.summary_sub)
        emptyState = view.findViewById(R.id.empty_state)
        resultsSection = view.findViewById(R.id.results_section)
        resultsContainer = view.findViewById(R.id.results_container)
        illustInput = view.findViewById(R.id.illust_id_input)
        btnIllustTest = view.findViewById(R.id.btn_illust_test)
        illustResultContainer = view.findViewById(R.id.illust_result_container)
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
            resultsContainer.removeAllViews()
            list.forEach { resultsContainer.addView(buildTargetCard(it, resultsContainer)) }
        }
        viewModel.overall.observe(viewLifecycleOwner) { renderSummary(it) }
        viewModel.rawLog.observe(viewLifecycleOwner) { rawLogText.text = it }
        viewModel.illustRunning.observe(viewLifecycleOwner) { isRunning ->
            btnIllustTest.isEnabled = !isRunning
            btnIllustTest.alpha = if (isRunning) 0.6f else 1f
        }
        viewModel.illustReport.observe(viewLifecycleOwner) { report ->
            illustResultContainer.removeAllViews()
            if (report != null) {
                illustResultContainer.addView(buildTargetCard(report, illustResultContainer))
            }
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
    }

    private fun renderSummary(overall: OverallStatus?) {
        if (overall == null) {
            summaryCard.visibility = View.GONE
            return
        }
        summaryCard.visibility = View.VISIBLE
        val (labelRes, subRes, colorRes) = when (overall) {
            OverallStatus.CLEAN -> Triple(
                R.string.network_test_overall_clean,
                R.string.network_test_overall_clean_sub,
                R.color.v3_green,
            )
            OverallStatus.DEGRADED -> Triple(
                R.string.network_test_overall_degraded,
                R.string.network_test_overall_degraded_sub,
                R.color.v3_orange,
            )
            OverallStatus.POLLUTED -> Triple(
                R.string.network_test_overall_polluted,
                R.string.network_test_overall_polluted_sub,
                R.color.v3_danger,
            )
        }
        applyPill(summaryPill, "● " + getString(labelRes), colorRes)
        summarySub.setText(subRes)
    }

    private fun buildTargetCard(report: TargetReport, parent: ViewGroup): View {
        val card = layoutInflater.inflate(R.layout.item_network_test_target, parent, false)
        card.findViewById<TextView>(R.id.target_title).text = report.title
        card.findViewById<TextView>(R.id.target_subtitle).text = report.subtitle

        val (label, colorRes) = targetStatusStyle(report.status)
        applyPill(card.findViewById(R.id.target_status_pill), label, colorRes)

        val steps = card.findViewById<LinearLayout>(R.id.steps_container)
        steps.removeAllViews()
        report.steps.forEach { steps.addView(buildStepRow(it, steps)) }
        return card
    }

    private fun buildStepRow(step: TestStep, parent: ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.item_network_test_step, parent, false)
        val icon = row.findViewById<TextView>(R.id.step_icon)
        icon.text = stepIcon(step.status)
        icon.setTextColor(ContextCompat.getColor(requireContext(), stepColorRes(step.status)))

        row.findViewById<TextView>(R.id.step_label).text = step.label
        val detail = row.findViewById<TextView>(R.id.step_detail)
        if (step.detail.isNullOrBlank()) {
            detail.visibility = View.GONE
        } else {
            detail.visibility = View.VISIBLE
            detail.text = step.detail
        }
        return row
    }

    private fun targetStatusStyle(status: TargetStatus): Pair<String, Int> = when (status) {
        TargetStatus.RUNNING -> "测试中" to R.color.v3_blue
        TargetStatus.OK -> "通畅" to R.color.v3_green
        TargetStatus.DEGRADED -> "部分异常" to R.color.v3_orange
        TargetStatus.POLLUTED -> "DNS 污染" to R.color.v3_danger
        TargetStatus.FAILED -> "失败" to R.color.v3_danger
        TargetStatus.SKIPPED -> "已取消" to R.color.v3_text_3
    }

    private fun stepIcon(status: StepStatus): String = when (status) {
        StepStatus.OK -> "✓"
        StepStatus.WARN -> "⚠"
        StepStatus.FAIL -> "✗"
        StepStatus.RUNNING -> "…"
        StepStatus.INFO -> "•"
    }

    private fun stepColorRes(status: StepStatus): Int = when (status) {
        StepStatus.OK -> R.color.v3_green
        StepStatus.WARN -> R.color.v3_orange
        StepStatus.FAIL -> R.color.v3_danger
        StepStatus.RUNNING -> R.color.v3_blue
        StepStatus.INFO -> R.color.v3_text_3
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
