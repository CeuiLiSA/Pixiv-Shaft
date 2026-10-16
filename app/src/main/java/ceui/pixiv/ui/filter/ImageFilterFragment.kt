package ceui.pixiv.ui.filter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Params
import ceui.lisa.view.SeamlessCircularProgressIndicator
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.library.LibraryFilterViews
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.setTextWithIcon
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.hjq.toast.Toaster
import kotlin.math.max
import kotlinx.coroutines.launch

/**
 * 图片滤镜：给作品的某一页套上当下修图 App 里的热门滤镜，再按需微调，存一张新图或直接分享。
 *
 * 页面按 V3「作品 / 内容」配方：舞台（这一页，按住看原图）优先 → 「滤镜 | 调节」→ 滤镜条或调节项 →
 * 一条滑条 → 一个最强操作（「保存」实色胶囊）。宽屏横放时舞台在左、控制台在右。
 *
 * 性能分工见 [ImageFilterViewModel] 与 `shaft_image_filter.cpp`：预览与导出是同一个 native 内核，
 * 拖滑条时只渲染屏幕尺寸的小图，保存时才跑一次全分辨率。
 */
class ImageFilterFragment : Fragment(R.layout.fragment_image_filter) {

    private val model: ImageFilterViewModel by viewModels()
    private lateinit var palette: V3Palette
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var stage: FrameLayout
    private lateinit var preview: FilterPreviewView
    private lateinit var flash: View
    private lateinit var originalBadge: TextView
    private lateinit var holdHint: TextView
    private lateinit var loadingBox: LinearLayout
    private lateinit var loadingRing: SeamlessCircularProgressIndicator
    private lateinit var loadingCaption: TextView
    private lateinit var failedBox: LinearLayout

    private lateinit var modeGroup: LibraryFilterViews.ChoiceGroup
    private lateinit var resetButton: ImageView
    private lateinit var presetPanel: LinearLayout
    private lateinit var adjustPanel: LinearLayout
    private lateinit var presetScroll: HorizontalScrollView
    private val presetItems = ArrayList<PresetItem>()
    private lateinit var intensityRow: SliderRow
    private lateinit var toolGroup: LibraryFilterViews.ChoiceGroup
    private lateinit var adjustRow: SliderRow

    private lateinit var shareButton: ImageView
    private lateinit var saveButton: TextView

    private var previewSource: Bitmap? = null
    private var savedLabelUntil = 0L
    private var hintShown = false

    /**
     * 预览小图的长边：按舞台大约能占到的屏幕尺寸取，而不是整块屏幕的长边 —— 预览每一帧都要整张渲一遍，
     * 多出来的像素只会拖慢滑条，屏幕上也看不出来。
     */
    private val previewMaxEdge: Int
        get() = resources.displayMetrics.let { max(it.widthPixels, (it.heightPixels * .62f).toInt()) }
            .coerceAtMost(MAX_PREVIEW_EDGE)
    private val thumbPx: Int get() = requireContext().dp(THUMB_DP)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        palette = V3Palette.from(ctx)
        val args = requireArguments()
        val illust = requireNotNull(BundleCompat.getSerializable(args, ARG_ILLUST, Illust::class.java))
        val page = args.getInt(ARG_PAGE)
        val content = view.findViewById<FrameLayout>(R.id.image_filter_content)
        setupV3Toolbar(view, getString(R.string.image_filter), content = content)

        buildStage(ctx, illust)
        val modeRow = buildModeRow(ctx)
        buildPresetPanel(ctx)
        buildAdjustPanel(ctx)
        val actions = buildActions(ctx)
        layOut(ctx, content, modeRow, actions)

        model.start(illust, page, previewMaxEdge, thumbPx)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.load.collect(::renderLoad) }
                launch { model.rendered.collect(::renderFrame) }
                launch { model.thumbs.collect(::renderThumbs) }
                launch { model.state.collect(::renderState) }
                launch { model.adjustMode.collect(::renderMode) }
                launch { model.adjustTool.collect { renderTool() } }
                launch { model.exporting.collect(::renderExporting) }
                launch { model.results.collect(::onExportResult) }
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        model.onDisplayed(null)
        presetItems.clear()
        super.onDestroyView()
    }

    // ── 布局 ────────────────────────────────────────────────────────

    /** 手机竖放：上舞台、下控制台；宽屏横放：舞台在左，控制台 380dp 在右。 */
    private fun layOut(ctx: Context, content: FrameLayout, modeRow: View, actions: View) {
        val cfg = resources.configuration
        val wide = cfg.screenWidthDp >= 600 && cfg.screenWidthDp > cfg.screenHeightDp
        // 两个面板叠在一起、不用的那个 INVISIBLE：容器高度取两者较高的，切模式时舞台不跳。
        val panels = FrameLayout(ctx).apply {
            addView(presetPanel, FrameLayout.LayoutParams(-1, -2))
            addView(adjustPanel, FrameLayout.LayoutParams(-1, -2))
        }
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = ctx.dp(16); rightMargin = ctx.dp(8); topMargin = ctx.dp(if (wide) 0 else 8)
            })
            addView(panels, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = ctx.dp(16); rightMargin = ctx.dp(16); topMargin = ctx.dp(8); bottomMargin = ctx.dp(16)
            })
        }
        val page = LinearLayout(ctx).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (wide) {
            page.addView(stage, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(8), ctx.dp(16))
            })
            // 控制台高度随字号涨，横放手机只剩 ~330dp：字号放大后放不下就竖滑，「保存」不会被底边裁掉。
            // 放得下时 ScrollView 包住内容高度，照旧垂直居中。
            val scroller = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(controls)
            }
            page.addView(scroller, LinearLayout.LayoutParams(ctx.dp(380), -2))
        } else {
            page.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                setMargins(ctx.dp(16), ctx.dp(12), ctx.dp(16), 0)
            })
            page.addView(controls, LinearLayout.LayoutParams(-1, -2))
        }
        val maxWidth = ctx.dp(if (wide) 1200 else 720)
        content.addView(page, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        content.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left, maxWidth)
            if (page.layoutParams.width != width) {
                page.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
            }
        }
    }

    /** 舞台：26dp 容器里完整显示这一页；按住看原图，左上角浮出「原图」玻璃小标。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildStage(ctx: Context, illust: Illust) {
        preview = FilterPreviewView(ctx).apply {
            contentDescription = getString(R.string.image_filter_stage_desc, illust.title.orEmpty())
            setOnTouchListener { v, e ->
                if (model.load.value !is FilterLoad.Ready) return@setOnTouchListener false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        holdOriginal(true)
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> holdOriginal(false)
                }
                true
            }
        }
        flash = View(ctx).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        originalBadge = glassPill(ctx, getString(R.string.image_filter_original_badge)).apply { isVisible = false }
        holdHint = glassPill(ctx, getString(R.string.image_filter_hold_hint)).apply { alpha = 0f }
        loadingRing = LayoutInflater.from(ctx).inflate(R.layout.item_progress, null, false) as SeamlessCircularProgressIndicator
        loadingCaption = ctx.label(getString(R.string.now_loading), 12f, 500, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        loadingBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = ctx.dp(160)
            setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
            background = shape(ctx.dpF(12f), 0xB3000000.toInt())
            addView(loadingRing, LinearLayout.LayoutParams(-2, -2))
            addView(loadingCaption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
        }
        failedBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isVisible = false
            setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(16))
            background = shape(ctx.dpF(16f), 0xB3000000.toInt())
            addView(ctx.label(getString(R.string.image_filter_load_failed), 14f, 500, Color.WHITE).apply {
                gravity = Gravity.CENTER
            })
            addView(ctx.pillButton(getString(R.string.retry), icon = R.drawable.ic_baseline_refresh_48) {
                model.retry(previewMaxEdge, thumbPx)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(12) })
        }
        stage = FrameLayout(ctx).apply {
            background = shape(ctx.dpF(26f), palette.cardFill, palette.cardHairline, ctx.hairlinePx())
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(flash, FrameLayout.LayoutParams(-1, -1))
            addView(loadingBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(failedBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(originalBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            })
            addView(holdHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(14))
            })
        }
    }

    /** 「滤镜 | 调节」连通组 + 末端的重置。 */
    private fun buildModeRow(ctx: Context): View {
        modeGroup = LibraryFilterViews(ctx).connectedGroup(
            listOf(getString(R.string.image_filter_mode_presets), getString(R.string.image_filter_mode_adjust)),
        ) { model.setAdjustMode(it == 1) }
        resetButton = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_refresh_48)
            imageTintList = ColorStateList.valueOf(palette.textAccent)
            val pad = ctx.dp(13)
            setPadding(pad, pad, pad, pad)
            background = ctx.ripple(null, shape(999f, Color.WHITE))
            contentDescription = getString(R.string.image_filter_reset)
            isClickable = true
            isFocusable = true
            setOnClickListener { reset() }
            pressScale()
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(modeGroup.view, LinearLayout.LayoutParams(0, -2, 1f))
            addView(resetButton, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)).apply { marginStart = ctx.dp(4) })
        }
    }

    /** 滤镜条：按来源分组横滑，每组上方一枚小标；下面是强度滑条。 */
    private fun buildPresetPanel(ctx: Context) {
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(16), 0, ctx.dp(8), 0)
        }
        val groups = listOf<FilterGroup?>(null) + FilterGroup.entries
        for (group in groups) {
            val presets = FilterPreset.entries.filter { it.group == group }
            val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            // 原图那一列也占一行组标，保证所有缩略图顶对齐。
            column.addView(ctx.label(group?.let { getString(it.label) } ?: " ", 11f, 600, ctx.color(R.color.v3_text_2)).apply {
                maxLines = 1
                setPadding(ctx.dp(2), 0, 0, ctx.dp(6))
            })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (preset in presets) {
                val item = PresetItem(ctx, preset)
                presetItems += item
                row.addView(item.root, LinearLayout.LayoutParams(ctx.dp(THUMB_DP + 8), -2))
            }
            column.addView(row)
            strip.addView(column, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(10) })
        }
        presetScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(ctx.dp(24))
            addView(strip)
        }
        intensityRow = SliderRow(ctx, bipolar = false, neutral = 100) { model.setIntensity(it) }
        intensityRow.title.text = getString(R.string.image_filter_intensity)
        presetPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(presetScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(intensityRow.root, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = ctx.dp(16); rightMargin = ctx.dp(12); topMargin = ctx.dp(2)
            })
        }
    }

    /** 调节：一行横滑的调节项胶囊（改过的带数值），下面是当前项的滑条。 */
    private fun buildAdjustPanel(ctx: Context) {
        toolGroup = LibraryFilterViews(ctx).choiceChips(adjustLabels(model.state.value), scroll = true) {
            model.setAdjustTool(it)
        }
        // 胶囊条的首尾留白对齐本页 16dp 的边。
        ((toolGroup.view as HorizontalScrollView).getChildAt(0)).setPaddingRelative(ctx.dp(16), 0, ctx.dp(8), 0)
        adjustRow = SliderRow(ctx, bipolar = true, neutral = 0) { value ->
            model.setAdjust(currentTool(), value)
        }
        adjustPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolGroup.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(adjustRow.root, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = ctx.dp(16); rightMargin = ctx.dp(12); topMargin = ctx.dp(2)
            })
        }
    }

    private fun buildActions(ctx: Context): View {
        shareButton = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_share_black_24dp)
            imageTintList = ColorStateList.valueOf(palette.textAccent)
            val pad = ctx.dp(16)
            setPadding(pad, pad, pad, pad)
            background = ctx.ripple(palette.pillSecondary(999f, ctx.hairlinePx()), shape(999f, Color.WHITE))
            contentDescription = getString(R.string.share)
            isClickable = true
            isFocusable = true
            setOnClickListener { model.export(FilterExport.SHARE) }
            pressScale()
        }
        saveButton = ctx.pillButton(getString(R.string.image_filter_save)) { model.export(FilterExport.SAVE) }.apply {
            textSize = 15f
            minHeight = ctx.dp(56)
            setTextWithIcon(getString(R.string.image_filter_save), R.drawable.ic_file_download_black_24dp)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(shareButton, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
            addView(saveButton, LinearLayout.LayoutParams(0, ctx.dp(56), 1f).apply { marginStart = ctx.dp(12) })
        }
    }

    // ── 渲染 ────────────────────────────────────────────────────────

    private fun renderLoad(state: FilterLoad) {
        val ready = state is FilterLoad.Ready
        loadingBox.isVisible = state is FilterLoad.Loading
        failedBox.isVisible = state is FilterLoad.Failed
        listOf<View>(presetScroll, intensityRow.root, toolGroup.view, adjustRow.root)
            .forEach { it.alpha = if (ready) 1f else .38f }
        presetItems.forEach { it.root.isEnabled = ready }
        intensityRow.slider.isEnabled = ready
        adjustRow.slider.isEnabled = ready
        renderExporting(model.exporting.value)
        renderReset(model.state.value)
        when (state) {
            is FilterLoad.Loading -> {
                loadingRing.isIndeterminate = state.percent == null
                state.percent?.let { loadingRing.setProgressCompat(it, true) }
                loadingCaption.text = if (state.percent != null) {
                    getString(R.string.image_filter_loading_percent, state.percent)
                } else {
                    getString(R.string.now_loading)
                }
            }
            is FilterLoad.Ready -> {
                previewSource = state.preview
                preview.setImages(state.preview, model.rendered.value)
                showHoldHintOnce()
            }
            FilterLoad.Failed -> Unit
        }
    }

    private fun renderFrame(frame: Bitmap?) {
        preview.setImages(previewSource, frame)
        model.onDisplayed(frame)
    }

    private fun renderThumbs(thumbs: List<Bitmap?>) {
        presetItems.forEach { item -> item.setThumb(thumbs.getOrNull(item.preset.ordinal)) }
    }

    private fun renderState(state: FilterState) {
        presetItems.forEach { it.setChosen(it.preset == state.preset) }
        // 原图没有「强度」可调：滑条留着位置但收起来，免得切滤镜时面板跳动。
        intensityRow.root.visibility = if (state.preset == FilterPreset.ORIGINAL) View.INVISIBLE else View.VISIBLE
        intensityRow.setValue(state.intensity)
        toolGroup.setLabels(adjustLabels(state))
        adjustRow.setValue(state[currentTool()])
        renderReset(state)
    }

    private fun renderReset(state: FilterState) {
        val enabled = state.isModified && model.load.value is FilterLoad.Ready
        resetButton.isEnabled = enabled
        resetButton.alpha = if (enabled) 1f else .38f
    }

    private fun renderMode(adjust: Boolean) {
        modeGroup.select(if (adjust) 1 else 0)
        presetPanel.visibility = if (adjust) View.INVISIBLE else View.VISIBLE
        adjustPanel.visibility = if (adjust) View.VISIBLE else View.INVISIBLE
        if (!adjust) revealSelectedPreset()
    }

    private fun renderTool() {
        val tool = currentTool()
        toolGroup.select(tool.ordinal)
        adjustRow.title.text = getString(tool.label)
        adjustRow.setBipolar(tool.bipolar)
        adjustRow.setValue(model.state.value[tool])
    }

    private fun renderExporting(kind: FilterExport?) {
        val ready = model.load.value is FilterLoad.Ready
        val busy = kind != null
        shareButton.isEnabled = ready && !busy
        saveButton.isEnabled = ready && !busy
        shareButton.alpha = if (ready && !busy) 1f else .38f
        saveButton.alpha = if (ready) 1f else .38f
        when {
            kind == FilterExport.SAVE -> saveButton.setTextWithIcon(getString(R.string.image_filter_saving), null)
            kind == FilterExport.SHARE -> saveButton.setTextWithIcon(getString(R.string.image_filter_preparing), null)
            System.currentTimeMillis() >= savedLabelUntil ->
                saveButton.setTextWithIcon(getString(R.string.image_filter_save), R.drawable.ic_file_download_black_24dp)
        }
    }

    private fun onExportResult(result: FilterExportResult) {
        when (result) {
            FilterExportResult.Saved -> {
                flashStage()
                saveButton.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.VIRTUAL_KEY,
                )
                Toaster.show(getString(R.string.image_filter_saved_toast))
                savedLabelUntil = System.currentTimeMillis() + SAVED_LABEL_MS
                saveButton.setTextWithIcon(getString(R.string.image_filter_saved), R.drawable.ic_check_24dp)
                handler.postDelayed({
                    if (view != null) {
                        savedLabelUntil = 0L
                        renderExporting(model.exporting.value)
                    }
                }, SAVED_LABEL_MS)
            }
            is FilterExportResult.Share -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = result.mime
                    putExtra(Intent.EXTRA_STREAM, result.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share)))
            }
            is FilterExportResult.Failed ->
                Toaster.show(getString(R.string.save_image_failed, result.message))
        }
    }

    // ── 交互 ────────────────────────────────────────────────────────

    private fun currentTool(): FilterAdjust =
        FilterAdjust.entries.getOrElse(model.adjustTool.value) { FilterAdjust.entries.first() }

    private fun adjustLabels(state: FilterState): List<CharSequence> = FilterAdjust.entries.map { a ->
        val v = state[a]
        if (v == 0) getString(a.label) else "${getString(a.label)} ${signed(v, a.bipolar)}"
    }

    private fun reset() {
        val previous = model.state.value
        if (!previous.isModified) return
        model.reset()
        Snackbar.make(requireView(), R.string.image_filter_reset_done, Snackbar.LENGTH_LONG)
            .setAction(R.string.ugoira_frames_undo) { model.restore(previous) }
            .show()
    }

    private fun holdOriginal(hold: Boolean) {
        preview.showOriginal = hold
        originalBadge.isVisible = hold
        if (hold) {
            holdHint.animate().cancel()
            holdHint.alpha = 0f
        }
    }

    /** 进页面后在舞台底部提示一次「按住看原图」，2.5 秒后淡出。关闭系统动画时直接不显示。 */
    private fun showHoldHintOnce() {
        if (hintShown || !motionEnabled()) return
        hintShown = true
        holdHint.animate().alpha(1f).setDuration(200).withEndAction {
            holdHint.animate().alpha(0f).setStartDelay(2500).setDuration(300).start()
        }.start()
    }

    private fun revealSelectedPreset() {
        val item = presetItems.firstOrNull { it.preset == model.state.value.preset } ?: return
        presetScroll.doOnLayout {
            val parent = item.root.parent as View   // 组内的行
            val column = parent.parent as View      // 一组
            val x = column.left + parent.left + item.root.left
            val target = (x - (presetScroll.width - item.root.width) / 2).coerceAtLeast(0)
            presetScroll.scrollTo(target, 0)
        }
    }

    /** 快门式的一次白闪：告诉用户「存下来了」。关闭系统动画时不闪。 */
    private fun flashStage() {
        if (!motionEnabled()) return
        flash.animate().cancel()
        flash.alpha = .35f
        flash.animate().alpha(0f).setDuration(260).start()
    }

    // ── 小零件 ──────────────────────────────────────────────────────

    private fun glassPill(ctx: Context, text: String): TextView =
        ctx.label(text, 12f, 600, palette.floatingPillContent).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            background = palette.floatingPillBg(999f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** 滤镜条的一格：64dp 缩略图（17dp 圆角）+ 名字。选中 = 2.5dp 主题描边 + 主题色 600 字重。 */
    private inner class PresetItem(ctx: Context, val preset: FilterPreset) {
        private var shown: Bitmap? = null
        private var chosen: Boolean? = null
        private val ring = shape(ctx.dpF(17f), Color.TRANSPARENT, palette.textAccent, (ctx.dpF(2.5f) + .5f).toInt())
        private val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(ctx.dpF(17f), palette.alpha10)
            clipToOutline = true
            foreground = ring
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val name = ctx.label(getString(preset.label), 12f, 500, ctx.color(R.color.v3_text_2)).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(4), 0, ctx.dp(4), ctx.dp(4))
            addView(thumb, LinearLayout.LayoutParams(ctx.dp(THUMB_DP), ctx.dp(THUMB_DP)))
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) })
            contentDescription = getString(preset.label)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (model.state.value.preset != preset) it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                model.selectPreset(preset)
            }
            pressScale()
        }

        fun setThumb(bitmap: Bitmap?) {
            if (shown === bitmap) return
            shown = bitmap
            thumb.setImageBitmap(bitmap)
        }

        fun setChosen(value: Boolean) {
            if (chosen == value) return
            chosen = value
            ring.alpha = if (value) 255 else 0
            name.typeface = requireContext().v3Font(if (value) 600 else 500)
            name.setTextColor(if (value) palette.textAccent else requireContext().color(R.color.v3_text_2))
            root.isSelected = value
        }
    }

    /**
     * 一条「名字 · 滑条 · 数值」。双向项（亮度、色温…）的滑条从中间往两边拉，过 0 时轻震一下；
     * 点数值复位到 [neutral]（不起作用的那一档：调节项不论单向双向都是 0，强度是 100）。
     */
    private inner class SliderRow(
        ctx: Context,
        bipolar: Boolean,
        private val neutral: Int,
        private val onChange: (Int) -> Unit,
    ) {
        private var bipolar = bipolar
        val title: TextView = ctx.label("", 14f, 600).apply {
            maxLines = 1
            minWidth = ctx.dp(56)
        }
        private val value: TextView = ctx.label("", 14f, 600, palette.textAccent).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            minWidth = ctx.dp(48)
            minHeight = ctx.dp(48)
            background = ctx.ripple(null, shape(999f, Color.WHITE))
            isClickable = true
            setOnClickListener { onChange(neutral) }
        }
        val slider: Slider = Slider(
            ContextThemeWrapper(ctx, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar),
        ).apply {
            stepSize = 1f
            labelBehavior = LabelFormatter.LABEL_GONE
            isTickVisible = false
            trackActiveTintList = ColorStateList.valueOf(palette.primary)
            trackInactiveTintList = ColorStateList.valueOf(palette.alpha20)
            thumbTintList = ColorStateList.valueOf(palette.primary)
            haloTintList = ColorStateList.valueOf(palette.alpha20)
            addOnChangeListener { s, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                if (this@SliderRow.bipolar && v == 0f) s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onChange(v.toInt())
            }
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(48)
            addView(title, LinearLayout.LayoutParams(-2, -2))
            addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value, LinearLayout.LayoutParams(-2, -2))
        }

        init { setBipolar(bipolar) }

        fun setBipolar(b: Boolean) {
            bipolar = b
            // 先把值放回两个区间都合法的 0，再改区间：Slider 在绘制时校验，中间态越界会直接抛。
            slider.value = 0f
            slider.valueFrom = if (b) -100f else 0f
            slider.valueTo = 100f
            slider.isCentered = b
        }

        fun setValue(v: Int) {
            val clamped = v.coerceIn(slider.valueFrom.toInt(), slider.valueTo.toInt())
            if (slider.value != clamped.toFloat()) slider.value = clamped.toFloat()
            value.text = signed(clamped, bipolar)
            slider.contentDescription = "${title.text} ${value.text}"
        }
    }

    companion object {
        private const val ARG_ILLUST = "illust"
        private const val ARG_PAGE = "page"
        private const val THUMB_DP = 64
        private const val SAVED_LABEL_MS = 1600L

        /** 预览小图的长边上限：再大屏幕上也看不出差别，像素却按平方涨。 */
        private const val MAX_PREVIEW_EDGE = 2048

        private fun signed(v: Int, bipolar: Boolean): String = if (bipolar && v > 0) "+$v" else v.toString()

        fun newInstance(illust: Illust, page: Int) = ImageFilterFragment().apply {
            arguments = bundleOf(ARG_ILLUST to illust, ARG_PAGE to page)
        }
    }
}

/** 打开某一页的滤镜。动图的 original 是 zip，调用方不该为它摆出入口。 */
fun openImageFilter(context: Context, illust: Illust, page: Int) {
    context.startActivity(
        Intent(context, TemplateActivity::class.java)
            .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.IMAGE_FILTER.key)
            .putExtra(Params.CONTENT, illust)
            .putExtra(Params.INDEX, page),
    )
}
