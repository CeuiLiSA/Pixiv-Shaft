package ceui.pixiv.ui.novel.reader.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import ceui.lisa.databinding.FragmentReaderSettingsBinding
import ceui.lisa.databinding.ItemReaderSettingScrollBinding
import ceui.lisa.databinding.ItemReaderSettingSegmentedBinding
import ceui.lisa.databinding.ItemReaderSettingSliderBinding
import ceui.lisa.databinding.ItemReaderSettingSwitchBinding
import ceui.pixiv.ui.novel.reader.model.FlipMode
import ceui.pixiv.ui.novel.reader.model.ImagePlacement
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.model.ScreenOrientation
import ceui.pixiv.ui.novel.reader.paginate.TypefaceProvider
import ceui.pixiv.ui.novel.reader.settings.PresetFonts
import ceui.pixiv.ui.novel.reader.settings.ReaderFont
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BottomSheet panel exposing every live-tunable reader setting: typography,
 * theme, flip mode, screen, and image handling. Layout lives in XML
 * (fragment_reader_settings.xml + section_* + item_*); this class only wires
 * the dynamic pieces (slider ranges, segmented button lists, theme / font
 * swatches) and pipes changes into [ReaderSettings].
 */
class ReaderSettingsPanel : BottomSheetDialogFragment() {

    private var _binding: FragmentReaderSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext()).apply {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            setOnShowListener {
                val sheet = findViewById<View>(
                    com.google.android.material.R.id.design_bottom_sheet,
                )
                sheet?.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReaderSettingsBinding.inflate(inflater, container, false)
        val ctx = requireContext()
        bindTypography(ctx)
        bindTheme(ctx)
        bindFlip(ctx)
        bindScreen(ctx)
        bindImage(ctx)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---- Sections ---------------------------------------------------------

    private fun bindTypography(ctx: Context) {
        val s = binding.sectionTypography
        s.rowFontSize.bindIntSlider(
            "字号", ReaderSettings.FONT_SIZE_MIN, ReaderSettings.FONT_SIZE_MAX,
            ReaderSettings.fontSizeSp, "sp",
        ) { ReaderSettings.fontSizeSp = it }
        s.rowLineSpacing.bindFloatSlider(
            "行距", 1.0f, 2.8f, 18, ReaderSettings.lineSpacing,
        ) { ReaderSettings.lineSpacing = it }
        s.rowParagraphSpacing.bindFloatSlider(
            "段间距", 0f, 2.5f, 25, ReaderSettings.paragraphSpacingLines, suffix = "行",
        ) { ReaderSettings.paragraphSpacingLines = it }
        s.rowHorizontalMargin.bindIntSlider(
            "左右边距", 0, 64, ReaderSettings.horizontalMarginDp, "dp",
        ) { ReaderSettings.horizontalMarginDp = it }
        s.rowVerticalMargin.bindIntSlider(
            "上下边距", 0, 96, ReaderSettings.verticalMarginDp, "dp",
        ) { ReaderSettings.verticalMarginDp = it }
        s.rowFirstLineIndent.bindSegmented(
            ctx, "首行缩进",
            listOf("无" to 0, "1字" to 1, "2字" to 2, "3字" to 3, "4字" to 4),
            ReaderSettings.firstLineIndent,
        ) { ReaderSettings.firstLineIndent = it }
        s.rowLetterSpacing.bindFloatSlider(
            "字间距", -0.05f, 0.25f, 30, ReaderSettings.letterSpacing, digits = 2,
        ) { ReaderSettings.letterSpacing = it }
        s.rowBold.bindSwitch("加粗", ReaderSettings.boldText) { ReaderSettings.boldText = it }
        s.rowFontPicker.bindFontPicker(ctx)
        s.rowFontWeight.bindIntSlider(
            "字重", 100, 900, ReaderSettings.fontWeight,
        ) { ReaderSettings.fontWeight = it }
    }

    private fun bindTheme(ctx: Context) {
        val s = binding.sectionTheme
        s.rowThemePicker.bindThemePicker(ctx)
        s.rowFollowSystemDark.bindSwitch(
            "跟随系统暗色", ReaderSettings.followSystemDarkMode,
        ) { ReaderSettings.followSystemDarkMode = it }
        s.rowUseSystemBrightness.bindSwitch(
            "屏幕亮度跟随系统", ReaderSettings.useSystemBrightness,
        ) { ReaderSettings.useSystemBrightness = it }
        s.rowCustomBrightness.bindFloatSlider(
            "自定义亮度", 0.01f, 1f, 99, ReaderSettings.customBrightness, digits = 2,
        ) { ReaderSettings.customBrightness = it }
        s.rowWarmFilter.bindFloatSlider(
            "暖色滤镜", 0f, 0.6f, 60, ReaderSettings.warmFilterStrength, digits = 2,
        ) { ReaderSettings.warmFilterStrength = it }
    }

    private fun bindFlip(ctx: Context) {
        val s = binding.sectionFlip
        s.rowFlipMode.bindSegmented(
            ctx, "翻页动画",
            listOf("仿真" to FlipMode.Simulation, "覆盖" to FlipMode.Cover, "平移" to FlipMode.Slide, "无" to FlipMode.None, "滚动" to FlipMode.Scroll),
            ReaderSettings.flipMode,
        ) { ReaderSettings.flipMode = it }
        s.rowVolumeKeyFlip.bindSwitch(
            "音量键翻页", ReaderSettings.volumeKeyFlip,
        ) { ReaderSettings.volumeKeyFlip = it }
        s.rowTapZoneReversed.bindSwitch(
            "反转左右点击区", ReaderSettings.tapZoneReversed,
        ) { ReaderSettings.tapZoneReversed = it }
        s.rowAutoPageInterval.bindIntSlider(
            "自动翻页间隔", 5, 60, ReaderSettings.autoPageIntervalSec, "秒",
        ) { ReaderSettings.autoPageIntervalSec = it }
    }

    private fun bindScreen(ctx: Context) {
        val s = binding.sectionScreen
        s.rowOrientation.bindSegmented(
            ctx, "方向",
            listOf("自动" to ScreenOrientation.Auto, "竖屏" to ScreenOrientation.Portrait, "横屏" to ScreenOrientation.Landscape),
            ReaderSettings.screenOrientation,
        ) { ReaderSettings.screenOrientation = it }
        s.rowImmersive.bindSwitch(
            "沉浸式（隐藏状态栏）", ReaderSettings.immersive,
        ) { ReaderSettings.immersive = it }
        s.rowKeepScreenOn.bindSwitch(
            "屏幕常亮", ReaderSettings.keepScreenOn,
        ) { ReaderSettings.keepScreenOn = it }
        s.rowShowTopProgress.bindSwitch(
            "显示顶部进度条", ReaderSettings.showTopProgress,
        ) { ReaderSettings.showTopProgress = it }
        s.rowShowBottomProgress.bindSwitch(
            "显示底部进度条", ReaderSettings.showBottomProgress,
        ) { ReaderSettings.showBottomProgress = it }
        s.rowTouchLocked.bindSwitch(
            "锁定触控（防误触）", ReaderSettings.touchLocked,
        ) { ReaderSettings.touchLocked = it }
        s.rowEyeBreak.bindIntSlider(
            "护眼提醒", 0, 120, ReaderSettings.eyeBreakReminderMinutes, "分钟",
        ) { ReaderSettings.eyeBreakReminderMinutes = it }
    }

    private fun bindImage(ctx: Context) {
        val s = binding.sectionImage
        s.rowImagePlacement.bindSegmented(
            ctx, "垂直位置",
            listOf("顶部" to ImagePlacement.Top, "居中" to ImagePlacement.Center, "底部" to ImagePlacement.Bottom),
            ReaderSettings.imagePlacement,
        ) { ReaderSettings.imagePlacement = it }
        s.rowImageScale.bindSegmented(
            ctx, "缩放模式",
            listOf("适应" to ImageScaleMode.Fit, "填充" to ImageScaleMode.Fill, "原始" to ImageScaleMode.Original),
            ReaderSettings.imageScaleMode,
        ) { ReaderSettings.imageScaleMode = it }
        s.rowPreloadImage.bindIntSlider(
            "预加载图片", 0, 8, ReaderSettings.preloadImageAhead, "页",
        ) { ReaderSettings.preloadImageAhead = it }
    }

    // ---- Item binders -----------------------------------------------------

    private fun ItemReaderSettingSliderBinding.bindIntSlider(
        label: String,
        min: Int,
        max: Int,
        initial: Int,
        suffix: String = "",
        onChange: (Int) -> Unit,
    ) {
        labelText.text = label
        seekBar.max = max - min
        seekBar.progress = (initial - min).coerceIn(0, seekBar.max)
        fun render() { valueText.text = "${seekBar.progress + min}$suffix" }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                render()
                if (fromUser) onChange(seekBar.progress + min)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        render()
    }

    private fun ItemReaderSettingSliderBinding.bindFloatSlider(
        label: String,
        min: Float,
        max: Float,
        steps: Int,
        initial: Float,
        suffix: String = "",
        digits: Int = 1,
        onChange: (Float) -> Unit,
    ) {
        labelText.text = label
        seekBar.max = steps
        val normalized = ((initial - min) / (max - min)).coerceIn(0f, 1f)
        seekBar.progress = (normalized * steps).toInt()
        fun currentValue(): Float = min + (seekBar.progress.toFloat() / steps) * (max - min)
        fun render() {
            val format = if (digits == 0) "%.0f" else "%.${digits}f"
            valueText.text = String.format(format, currentValue()) + suffix
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                render()
                if (fromUser) onChange(currentValue())
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        render()
    }

    private fun ItemReaderSettingSwitchBinding.bindSwitch(
        label: String,
        current: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        labelText.text = label
        switchControl.isChecked = current
        switchControl.setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun <T> ItemReaderSettingSegmentedBinding.bindSegmented(
        ctx: Context,
        label: String,
        options: List<Pair<String, T>>,
        current: T,
        onChange: (T) -> Unit,
    ) {
        labelText.text = label
        val container = buttonsContainer
        container.removeAllViews()
        options.forEach { (text, value) ->
            val button = Button(ctx).apply {
                this.text = text
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    weight = 1f
                    marginEnd = dp(ctx, 6f)
                }
                tag = value
                setOnClickListener {
                    @Suppress("UNCHECKED_CAST")
                    val v = tag as T
                    onChange(v)
                    applySegmentSelection(container, v)
                }
            }
            container.addView(button)
        }
        applySegmentSelection(container, current)
    }

    private fun <T> applySegmentSelection(container: LinearLayout, selected: T) {
        for (i in 0 until container.childCount) {
            val b = container.getChildAt(i) as Button
            val isSelected = b.tag == selected
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(container.context, 6f).toFloat()
                setColor(if (isSelected) 0xFF5B6EFF.toInt() else 0x1A000000)
            }
            b.setTextColor(if (isSelected) Color.WHITE else Color.BLACK)
        }
    }

    // ---- Theme picker -----------------------------------------------------

    private fun ItemReaderSettingScrollBinding.bindThemePicker(ctx: Context) {
        labelText.text = "主题"
        val inner = itemsContainer
        inner.removeAllViews()
        val size = dp(ctx, 56f)
        val selectedId = ReaderSettings.themeId
        val items = mutableListOf<View>()
        ReaderTheme.PRESETS.forEach { theme ->
            val swatch = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(size, size + dp(ctx, 24f)).apply {
                    marginEnd = dp(ctx, 14f)
                }
            }
            val circle = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.backgroundColor)
                    setStroke(
                        dp(ctx, 2f),
                        if (theme.id == selectedId) 0xFF5B6EFF.toInt() else 0x22000000,
                    )
                }
            }
            val text = TextView(ctx).apply {
                this.text = theme.displayName
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                )
            }
            swatch.addView(circle)
            swatch.addView(text)
            swatch.setOnClickListener {
                ReaderSettings.themeId = theme.id
                items.forEach { v ->
                    val c = (v as FrameLayout).getChildAt(0)
                    val bg = c.background as GradientDrawable
                    val id = v.tag as String
                    bg.setStroke(dp(ctx, 2f), if (id == theme.id) 0xFF5B6EFF.toInt() else 0x22000000)
                }
            }
            swatch.tag = theme.id
            items += swatch
            inner.addView(swatch)
        }
    }

    // ---- Font picker ------------------------------------------------------

    private fun ItemReaderSettingScrollBinding.bindFontPicker(ctx: Context) {
        labelText.text = "字体"
        val inner = itemsContainer
        inner.removeAllViews()
        val fonts: List<ReaderFont> = PresetFonts.BUILT_IN
        val items = mutableListOf<View>()
        fonts.forEach { font ->
            val button = TextView(ctx).apply {
                text = font.displayName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(ctx, 16f), dp(ctx, 10f), dp(ctx, 16f), dp(ctx, 10f))
                typeface = TypefaceProvider.resolve(ctx, font.id, 400, false)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(ctx, 10f) }
                tag = font.id
            }
            applyFontButtonBg(button, ReaderSettings.fontId == font.id)
            button.setOnClickListener {
                ReaderSettings.fontId = font.id
                items.forEach { v ->
                    val tv = v as TextView
                    applyFontButtonBg(tv, tv.tag == font.id)
                }
            }
            items += button
            inner.addView(button)
        }
    }

    private fun applyFontButtonBg(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(view.context, 6f).toFloat()
            setColor(if (selected) 0xFF5B6EFF.toInt() else 0x1A000000)
        }
        view.setTextColor(if (selected) Color.WHITE else Color.BLACK)
    }

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "ReaderSettingsPanel"
    }
}
