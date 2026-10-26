package ceui.pixiv.snapshot

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.pixiv.services.appServices
import ceui.pixiv.ui.library.LibraryFilterViews
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.card
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.iconTile
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.noticeCard
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.rowShape
import ceui.pixiv.witstudio.theme.sectionLabel
import ceui.pixiv.witstudio.theme.shape
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** 表单上还没开始的那份选择，转屏不丢。 */
class SnapshotBackfillDraftViewModel : ViewModel() {
    var config: SnapshotBackfillConfig? = null
}

/**
 * 补齐收藏快照（#1275），V3「下载 / 历史 / 管理」配方：一句说明 → 任务卡（状态、汇总数字、进度、
 * 唯一的主操作）→ 队列设置（连通分段行）→ 规则说明 → 整宽主按钮。没有 Hero。
 *
 * 页面只读 [SnapshotBackfill.state]、只发控制指令；队列在进程级宿主里跑，关掉页面不影响。
 * 选项对整个队列生效：一轮进行中时设置区只展示这一轮的值，停止后才能改。
 */
class SnapshotBackfillFragment : Fragment(R.layout.fragment_snapshot_backfill) {

    private val draftModel: SnapshotBackfillDraftViewModel by viewModels()

    private val backfill: SnapshotBackfill
        get() = requireContext().appServices().pixivActionQueue.snapshotBackfill

    private var locked = false
    private var renderedActions: String? = null

    private lateinit var statusCard: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var summaryLabel: TextView
    private lateinit var settledNumber: TextView
    private lateinit var totalLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var statsLabel: TextView
    private lateinit var detailLabel: TextView
    private lateinit var actionRow: LinearLayout

    private lateinit var scopeGroup: LibraryFilterViews.ChoiceGroup
    private lateinit var commentsSwitch: MaterialSwitch
    private lateinit var originalSwitch: MaterialSwitch
    private lateinit var wifiSwitch: MaterialSwitch
    private lateinit var settingsGroup: LinearLayout
    private lateinit var settingsHint: TextView
    private lateinit var startButton: TextView

    private val draft: SnapshotBackfillConfig
        get() = draftModel.config ?: backfill.lastConfig().also { draftModel.config = it }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val scroll = view.findViewById<NestedScrollView>(R.id.scroll_view)
        setupV3Toolbar(view, getString(R.string.snapshot_backfill_title), scroll)

        val host = view.findViewById<FrameLayout>(R.id.content_host)
        val column = buildColumn(ctx)
        host.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        // 宽屏上一整屏宽的表单读起来像横幅；同版本历史、广场收到 720dp 居中。
        host.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left, ctx.dp(MAX_WIDTH_DP))
            if (column.layoutParams.width != width) {
                column.layoutParams = FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                backfill.state.collect(::render)
            }
        }
    }

    // ── 搭建 ────────────────────────────────────────────────────────────────

    private fun buildColumn(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(32))

        addView(
            ctx.label(getString(R.string.snapshot_backfill_intro), 14f, 400, ctx.color(R.color.v3_text_2))
                .apply { lineHeightRatio(1.7f) },
            matchWidth(),
        )
        addView(buildStatusCard(ctx), matchWidth(top = 20))
        addView(ctx.sectionLabel(getString(R.string.snapshot_backfill_section_settings)), matchWidth(top = 28))
        addView(buildSettings(ctx), matchWidth(top = 10))
        settingsHint = ctx.label("", 12f, 400, ctx.color(R.color.v3_text_2)).apply { lineHeightRatio(1.6f) }
        addView(settingsHint, matchWidth(top = 10))
        addView(
            ctx.noticeCard(R.drawable.ic_v3_info_outline_24, getString(R.string.snapshot_backfill_notice)),
            matchWidth(top = 24),
        )
        startButton = TextView(ctx).apply {
            text = getString(R.string.snapshot_backfill_start)
            LibraryFilterViews(ctx).stylePrimaryAction(this)
            setOnClickListener { start() }
        }
        addView(startButton, matchWidth(top = 28))
    }

    private fun buildStatusCard(ctx: Context): LinearLayout {
        statusLabel = ctx.label("", 12f, 700)
        summaryLabel = ctx.label("", 15f, 600).apply { lineHeightRatio(1.4f) }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ctx.iconTile(R.drawable.ic_baseline_get_app_20, size = 44), LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(statusLabel)
                    addView(summaryLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(12) },
            )
        }
        settledNumber = ctx.label("0", 40f, 600).apply {
            fontFeatureSettings = "tnum"
            letterSpacing = -0.02f
        }
        totalLabel = ctx.label("", 14f, 400, ctx.color(R.color.v3_text_2)).apply { fontFeatureSettings = "tnum" }
        val numbers = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            addView(settledNumber)
            addView(totalLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dp(8) })
        }
        val palette = V3Palette.from(ctx)
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.v3_progress_pill)
            progressTintList = ColorStateList.valueOf(palette.primary)
            progressBackgroundTintList = ColorStateList.valueOf(ctx.color(R.color.v3_surface_3))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        statsLabel = ctx.label("", 12f, 400, ctx.color(R.color.v3_text_2)).apply {
            fontFeatureSettings = "tnum"
            lineHeightRatio(1.6f)
        }
        detailLabel = ctx.label("", 13f, 400).apply {
            lineHeightRatio(1.6f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.card(22)
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16))
            addView(header, matchWidth())
            addView(numbers, matchWidth(top = 18))
            addView(progress, LinearLayout.LayoutParams(-1, ctx.dp(6)).apply { topMargin = ctx.dp(12) })
            addView(statsLabel, matchWidth(top = 10))
            addView(detailLabel, matchWidth(top = 12))
            addView(actionRow, matchWidth(top = 12))
        }
        return statusCard
    }

    private fun buildSettings(ctx: Context): LinearLayout {
        val parts = LibraryFilterViews(ctx)
        // 范围是连通按钮组，直接铺在页面底上（同筛选面板的「无界平铺」）：它的未选中底是按 v3_bg
        // 合成的，套进卡片行里会比行底还暗，读成两个洞。
        val scopeBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ctx.label(getString(R.string.snapshot_backfill_scope), 14f, 600))
            scopeGroup = parts.connectedGroup(
                listOf(
                    getString(R.string.snapshot_backfill_scope_all),
                    getString(R.string.snapshot_backfill_scope_public),
                    getString(R.string.snapshot_backfill_scope_private),
                ),
            ) { index ->
                if (!locked) editDraft { it.copy(scope = SnapshotBackfillScope.entries[index]) }
            }
            addView(scopeGroup.view, matchWidth(top = 8))
        }
        val comments = switchRow(parts, R.string.snapshot_backfill_comments, R.string.snapshot_backfill_comments_hint) {
            editDraft { c -> c.copy(includeComments = !c.includeComments) }
        }
        commentsSwitch = comments.second
        val original = switchRow(parts, R.string.snapshot_backfill_original, R.string.snapshot_backfill_original_hint) {
            editDraft { c -> c.copy(includeOriginal = !c.includeOriginal) }
        }
        originalSwitch = original.second
        val wifi = switchRow(parts, R.string.snapshot_backfill_wifi, R.string.snapshot_backfill_wifi_hint) {
            editDraft { c -> c.copy(wifiOnly = !c.wifiOnly) }
        }
        wifiSwitch = wifi.second

        val rows = listOf(comments.first, original.first, wifi.first)
        val switchGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEachIndexed { i, row ->
                row.background = ctx.rowShape(i, rows.size)
                addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = ctx.dp(2) })
            }
        }
        settingsGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(scopeBlock, matchWidth())
            addView(switchGroup, matchWidth(top = 20))
        }
        return settingsGroup
    }

    /** 开关行 + 下方一句后果说明；整行热区由 [LibraryFilterViews.switchRow] 提供。 */
    private fun switchRow(
        parts: LibraryFilterViews,
        title: Int,
        hint: Int,
        onToggle: () -> Unit,
    ): Pair<View, MaterialSwitch> {
        val ctx = requireContext()
        val (row, switch) = parts.switchRow(getString(title)) { if (!locked) onToggle() }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(12))
            addView(row, matchWidth())
            addView(
                ctx.label(getString(hint), 12f, 400, ctx.color(R.color.v3_text_2)).apply {
                    lineHeightRatio(1.5f)
                    // 末端让出开关的宽度，说明文字不伸到开关底下。
                    setPadding(0, 0, ctx.dp(64), 0)
                },
                matchWidth(),
            )
        }
        return container to switch
    }

    // ── 渲染 ────────────────────────────────────────────────────────────────

    private fun render(state: SnapshotBackfillState) {
        val run = state.run
        locked = run != null
        renderForm(run?.config() ?: draft)
        startButton.isVisible = run == null
        statusCard.isVisible = run != null
        val free = getString(
            R.string.snapshot_backfill_free_space,
            Formatter.formatShortFileSize(requireContext(), requireContext().filesDir.usableSpace),
        )
        settingsHint.text = if (locked) getString(R.string.snapshot_backfill_locked_hint) + "\n" + free else free
        if (run != null) renderStatus(state, run)
    }

    private fun renderForm(config: SnapshotBackfillConfig) {
        scopeGroup.select(config.scope.ordinal)
        commentsSwitch.isChecked = config.includeComments
        originalSwitch.isChecked = config.includeOriginal
        wifiSwitch.isChecked = config.wifiOnly
        // 锁定时整组降透明、关掉点击：值仍看得见，读屏也会报「已停用」。
        settingsGroup.alpha = if (locked) LOCKED_ALPHA else 1f
        settingsGroup.setEnabledDeep(!locked)
    }

    private fun renderStatus(state: SnapshotBackfillState, run: SnapshotBackfillRun) {
        val ctx = requireContext()
        val palette = V3Palette.from(ctx)
        val phase = state.phase
        val (status, statusColor) = when (phase) {
            SnapshotBackfillPhase.FINISHED -> R.string.snapshot_backfill_status_finished to ctx.color(R.color.v3_green)
            SnapshotBackfillPhase.PAUSED_LOW_STORAGE -> R.string.snapshot_backfill_status_paused to ctx.color(R.color.v3_danger)
            SnapshotBackfillPhase.PAUSED -> R.string.snapshot_backfill_status_paused to ctx.color(R.color.v3_text_2)
            SnapshotBackfillPhase.RUNNING -> R.string.snapshot_backfill_status_running to palette.textAccent
            else -> R.string.snapshot_backfill_status_waiting to ctx.color(R.color.v3_text_2)
        }
        statusLabel.text = getString(status)
        statusLabel.setTextColor(statusColor)
        summaryLabel.text = summaryOf(run.config())

        val numbers = NumberFormat.getIntegerInstance()
        settledNumber.text = numbers.format(run.settled)
        totalLabel.text = getString(R.string.snapshot_backfill_total, run.discovered)
        progress.max = run.discovered.coerceAtLeast(1)
        progress.setProgress(run.settled, true)
        statsLabel.text = getString(
            R.string.snapshot_backfill_stats, run.done, run.existing, run.unavailable, run.failed,
        )

        detailLabel.text = when (phase) {
            SnapshotBackfillPhase.RUNNING -> when {
                state.currentTitle != null -> getString(R.string.snapshot_backfill_detail_generating, state.currentTitle)
                run.isScanning -> getString(R.string.snapshot_backfill_detail_scanning)
                else -> getString(R.string.snapshot_backfill_detail_working)
            }
            SnapshotBackfillPhase.PAUSED -> getString(R.string.snapshot_backfill_detail_paused)
            SnapshotBackfillPhase.PAUSED_LOW_STORAGE -> getString(R.string.snapshot_backfill_detail_low_storage)
            SnapshotBackfillPhase.WAITING_WIFI -> getString(R.string.snapshot_backfill_detail_wifi)
            SnapshotBackfillPhase.WAITING_NETWORK -> getString(R.string.snapshot_backfill_detail_network)
            SnapshotBackfillPhase.WAITING_LOGIN -> getString(R.string.snapshot_backfill_detail_login)
            SnapshotBackfillPhase.COOLING_DOWN -> getString(R.string.snapshot_backfill_detail_cooling)
            SnapshotBackfillPhase.FINISHED -> if (run.failedScans > 0) {
                getString(R.string.snapshot_backfill_detail_scan_failed)
            } else {
                getString(R.string.snapshot_backfill_detail_finished)
            }
            SnapshotBackfillPhase.IDLE -> ""
        }
        detailLabel.setTextColor(
            if (phase == SnapshotBackfillPhase.PAUSED_LOW_STORAGE) ctx.color(R.color.v3_danger) else ctx.color(R.color.v3_text_1)
        )
        renderActions(phase, run)
    }

    /** 一个区域只有一个最强操作：进行中「暂停」是浅色，暂停后「继续」是实色；「停止」恒为文字入口。 */
    private fun renderActions(phase: SnapshotBackfillPhase, run: SnapshotBackfillRun) {
        val retryable = run.failed + run.failedScans
        val key = "$phase:$retryable"
        if (key == renderedActions) return
        renderedActions = key
        val ctx = requireContext()
        actionRow.removeAllViews()
        fun add(view: View) = actionRow.addView(
            view,
            LinearLayout.LayoutParams(-2, -2).apply { if (actionRow.childCount > 0) marginStart = ctx.dp(8) },
        )
        when (phase) {
            SnapshotBackfillPhase.FINISHED -> if (retryable > 0) {
                add(ctx.pillButton(getString(R.string.snapshot_backfill_retry_failed, retryable)) { backfill.retryFailed() })
                add(ctx.pillButton(getString(R.string.snapshot_backfill_done_action), primary = false) { backfill.dismiss() })
            } else {
                add(ctx.pillButton(getString(R.string.snapshot_backfill_done_action)) { backfill.dismiss() })
            }
            SnapshotBackfillPhase.PAUSED, SnapshotBackfillPhase.PAUSED_LOW_STORAGE -> {
                add(ctx.pillButton(getString(R.string.snapshot_backfill_resume), icon = R.drawable.ic_v3_resume_rounded_24) { backfill.resume() })
                add(textAction(getString(R.string.snapshot_backfill_stop)) { confirmStop() })
            }
            else -> {
                add(ctx.pillButton(getString(R.string.snapshot_backfill_pause), primary = false, icon = R.drawable.ic_v3_pause_rounded_24) { backfill.pause() })
                add(textAction(getString(R.string.snapshot_backfill_stop)) { confirmStop() })
            }
        }
    }

    private fun textAction(text: CharSequence, action: () -> Unit): TextView {
        val ctx = requireContext()
        return ctx.label(text, 14f, 600, ctx.color(R.color.v3_text_2)).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(48)
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            background = ctx.ripple(null, shape(999f, Color.WHITE))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            pressScale()
        }
    }

    // ── 动作 ────────────────────────────────────────────────────────────────

    private fun start() {
        backfill.begin(draft)
        view?.findViewById<NestedScrollView>(R.id.scroll_view)?.smoothScrollTo(0, 0)
    }

    private fun confirmStop() {
        val run = backfill.state.value.run ?: return
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.snapshot_backfill_stop_title)
            .setMessage(getString(R.string.snapshot_backfill_stop_message, run.remaining))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.snapshot_backfill_stop, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                backfill.stop()
            }
            .create()
            .show()
    }

    private fun editDraft(transform: (SnapshotBackfillConfig) -> SnapshotBackfillConfig) {
        draftModel.config = transform(draft)
        renderForm(draft)
    }

    private fun summaryOf(config: SnapshotBackfillConfig): String = listOfNotNull(
        getString(
            when (config.scope) {
                SnapshotBackfillScope.ALL -> R.string.snapshot_backfill_summary_all
                SnapshotBackfillScope.PUBLIC -> R.string.snapshot_backfill_summary_public
                SnapshotBackfillScope.PRIVATE -> R.string.snapshot_backfill_summary_private
            }
        ),
        getString(if (config.includeOriginal) R.string.snapshot_backfill_summary_original else R.string.snapshot_backfill_summary_regular),
        getString(R.string.snapshot_backfill_summary_comments).takeIf { config.includeComments },
        getString(R.string.snapshot_backfill_summary_wifi).takeIf { config.wifiOnly },
    ).joinToString(" · ")

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = requireContext().dp(top)
    }

    private fun View.setEnabledDeep(enabled: Boolean) {
        isEnabled = enabled
        (this as? ViewGroup)?.children?.forEach { it.setEnabledDeep(enabled) }
    }

    private fun SnapshotBackfillRun.config() = SnapshotBackfillConfig(
        scope = SnapshotBackfillScope.entries.firstOrNull { it.name == scopeName } ?: SnapshotBackfillScope.ALL,
        includeComments = includeComments,
        includeOriginal = includeOriginal,
        wifiOnly = wifiOnly,
    )

    private companion object {
        const val MAX_WIDTH_DP = 720
        const val LOCKED_ALPHA = 0.5f
    }
}
