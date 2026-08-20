package ceui.pixiv.ui.novel.reader.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import ceui.lisa.R
import ceui.lisa.databinding.FragmentReaderSettingsBinding
import ceui.lisa.databinding.ItemReaderFontChipBinding
import ceui.lisa.databinding.ItemReaderSegmentOptionBinding
import ceui.lisa.databinding.ItemReaderSettingScrollBinding
import ceui.lisa.databinding.ItemReaderSettingSegmentedBinding
import ceui.lisa.databinding.ItemReaderSettingSliderBinding
import ceui.lisa.databinding.ItemReaderSettingSwitchBinding
import ceui.lisa.databinding.ItemReaderThemeSwatchBinding
import ceui.pixiv.ui.novel.reader.model.FlipMode
import ceui.pixiv.ui.novel.reader.model.ReadingDirection
import ceui.pixiv.ui.novel.reader.model.ImagePlacement
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.model.NovelIllustSource
import ceui.pixiv.ui.novel.reader.model.ScreenOrientation
import ceui.pixiv.ui.novel.reader.paginate.TypefaceProvider
import ceui.pixiv.ui.novel.reader.settings.PresetFonts
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import ceui.pixiv.utils.letDrawBehindNavBar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

/**
 * BottomSheet panel exposing every live-tunable reader setting: typography,
 * theme, flip mode, screen, and image handling. Layout lives in XML
 * (fragment_reader_settings.xml + section_* + item_*, MD3-E 分组卡); this class
 * only wires the dynamic pieces (slider ranges, segmented options, theme / font
 * swatches inflated from item_* via ViewBinding) and pipes changes into
 * [ReaderSettings]. 选中/激活态全部由 XML selector 承载,不在运行时拼 drawable。
 */
class ReaderSettingsPanel : BottomSheetDialogFragment() {

    private var _binding: FragmentReaderSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // edgeToEdge:让 window 画到导航栏底下,内容背景才能延伸进底部 safe area。
        return BottomSheetDialog(
            requireContext(),
            R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge,
        ).apply {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            setOnShowListener {
                val sheet = findViewById<View>(
                    com.google.android.material.R.id.design_bottom_sheet,
                )
                sheet?.setBackgroundColor(Color.TRANSPARENT)
                // 让 sheet 背景铺到屏幕底,消除底部 nav bar 那条透明缝/黑条。
                sheet?.letDrawBehindNavBar()
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
            getString(R.string.setting_font_size), ReaderSettings.FONT_SIZE_MIN, ReaderSettings.FONT_SIZE_MAX,
            ReaderSettings.fontSizeSp, "sp",
        ) { ReaderSettings.fontSizeSp = it }
        s.rowLineSpacing.bindFloatSlider(
            getString(R.string.setting_line_spacing), 1.0f, 2.8f, 18, ReaderSettings.lineSpacing,
        ) { ReaderSettings.lineSpacing = it }
        s.rowParagraphSpacing.bindFloatSlider(
            getString(R.string.setting_paragraph_spacing), 0f, 2.5f, 25, ReaderSettings.paragraphSpacingLines,
        ) { ReaderSettings.paragraphSpacingLines = it }
        s.rowHorizontalMargin.bindIntSlider(
            getString(R.string.setting_h_margin), 0, 64, ReaderSettings.horizontalMarginDp, "dp",
        ) { ReaderSettings.horizontalMarginDp = it }
        s.rowVerticalMargin.bindIntSlider(
            getString(R.string.setting_v_margin), 0, 96, ReaderSettings.verticalMarginDp, "dp",
        ) { ReaderSettings.verticalMarginDp = it }
        s.rowFirstLineIndent.bindSegmented(
            ctx, getString(R.string.setting_first_indent),
            listOf(getString(R.string.setting_indent_none) to 0, getString(R.string.setting_indent_1) to 1, getString(R.string.setting_indent_2) to 2, getString(R.string.setting_indent_3) to 3, getString(R.string.setting_indent_4) to 4),
            ReaderSettings.firstLineIndent,
        ) { ReaderSettings.firstLineIndent = it }
        s.rowLetterSpacing.bindFloatSlider(
            getString(R.string.setting_letter_spacing), -0.05f, 0.25f, 30, ReaderSettings.letterSpacing, digits = 2,
        ) { ReaderSettings.letterSpacing = it }
        s.rowBold.bindSwitch(getString(R.string.setting_bold), ReaderSettings.boldText) { ReaderSettings.boldText = it }
        s.rowFontPicker.bindFontPicker(ctx)
        s.rowFontWeight.bindIntSlider(
            getString(R.string.setting_font_weight), 100, 900, ReaderSettings.fontWeight,
        ) { ReaderSettings.fontWeight = it }
    }

    private fun bindTheme(ctx: Context) {
        val s = binding.sectionTheme
        s.rowThemePicker.bindThemePicker(ctx)
        s.rowFollowSystemDark.bindSwitch(
            getString(R.string.setting_follow_dark), ReaderSettings.followSystemDarkMode,
        ) { ReaderSettings.followSystemDarkMode = it }
        s.rowUseSystemBrightness.bindSwitch(
            getString(R.string.setting_system_brightness), ReaderSettings.useSystemBrightness,
        ) { ReaderSettings.useSystemBrightness = it }
        s.rowCustomBrightness.bindFloatSlider(
            getString(R.string.setting_custom_brightness), 0.01f, 1f, 99, ReaderSettings.customBrightness, digits = 2,
        ) { ReaderSettings.customBrightness = it }
        s.rowWarmFilter.bindFloatSlider(
            getString(R.string.setting_warm_filter), 0f, 0.6f, 60, ReaderSettings.warmFilterStrength, digits = 2,
        ) { ReaderSettings.warmFilterStrength = it }
    }

    private fun bindFlip(ctx: Context) {
        val s = binding.sectionFlip
        val flipRow = s.rowFlipMode
        val flipDivider = s.dividerFlipMode
        val flipVisibility = { horizontal: Boolean -> if (horizontal) View.VISIBLE else View.GONE }
        val isHorizontal = ReaderSettings.readingDirection == ReadingDirection.Horizontal
        flipRow.root.visibility = flipVisibility(isHorizontal)
        flipDivider.visibility = flipVisibility(isHorizontal)

        s.rowReadingDirection.bindSegmented(
            ctx, getString(R.string.setting_reading_direction),
            listOf(getString(R.string.setting_direction_horizontal) to ReadingDirection.Horizontal, getString(R.string.setting_direction_vertical) to ReadingDirection.Vertical),
            ReaderSettings.readingDirection,
        ) {
            ReaderSettings.readingDirection = it
            val vis = flipVisibility(it == ReadingDirection.Horizontal)
            flipRow.root.visibility = vis
            flipDivider.visibility = vis
        }
        flipRow.bindSegmented(
            ctx, getString(R.string.setting_flip_animation),
            listOf(getString(R.string.setting_flip_simulation) to FlipMode.Simulation, getString(R.string.setting_flip_cover) to FlipMode.Cover, getString(R.string.setting_flip_slide) to FlipMode.Slide, getString(R.string.setting_flip_none) to FlipMode.None),
            ReaderSettings.flipMode,
        ) { ReaderSettings.flipMode = it }
        s.rowVolumeKeyFlip.bindSwitch(
            getString(R.string.setting_volume_flip), ReaderSettings.volumeKeyFlip,
        ) { ReaderSettings.volumeKeyFlip = it }
        s.rowTapZoneReversed.bindSwitch(
            getString(R.string.setting_tap_reversed), ReaderSettings.tapZoneReversed,
        ) { ReaderSettings.tapZoneReversed = it }
        s.rowAutoPageInterval.bindIntSlider(
            getString(R.string.setting_auto_page_interval), 5, 60, ReaderSettings.autoPageIntervalSec, "s",
        ) { ReaderSettings.autoPageIntervalSec = it }
    }

    private fun bindScreen(ctx: Context) {
        val s = binding.sectionScreen
        s.rowOrientation.bindSegmented(
            ctx, getString(R.string.setting_orientation),
            listOf(getString(R.string.setting_orientation_auto) to ScreenOrientation.Auto, getString(R.string.setting_orientation_portrait) to ScreenOrientation.Portrait, getString(R.string.setting_orientation_landscape) to ScreenOrientation.Landscape),
            ReaderSettings.screenOrientation,
        ) { ReaderSettings.screenOrientation = it }
        s.rowImmersive.bindSwitch(
            getString(R.string.setting_immersive), ReaderSettings.immersive,
        ) { ReaderSettings.immersive = it }
        s.rowKeepScreenOn.bindSwitch(
            getString(R.string.setting_keep_screen_on), ReaderSettings.keepScreenOn,
        ) { ReaderSettings.keepScreenOn = it }
        s.rowTouchLocked.bindSwitch(
            getString(R.string.setting_touch_locked), ReaderSettings.touchLocked,
        ) { ReaderSettings.touchLocked = it }
        s.rowShowBottomProgress.bindSwitch(
            getString(R.string.setting_show_bottom_progress), ReaderSettings.showBottomProgress,
        ) { ReaderSettings.showBottomProgress = it }
        s.rowEyeBreak.bindIntSlider(
            getString(R.string.setting_eye_break), 0, 120, ReaderSettings.eyeBreakReminderMinutes, "min",
        ) { ReaderSettings.eyeBreakReminderMinutes = it }
    }

    private fun bindImage(ctx: Context) {
        val s = binding.sectionImage
        s.rowImagePlacement.bindSegmented(
            ctx, getString(R.string.setting_image_placement),
            listOf(getString(R.string.setting_image_top) to ImagePlacement.Top, getString(R.string.setting_image_center) to ImagePlacement.Center, getString(R.string.setting_image_bottom) to ImagePlacement.Bottom),
            ReaderSettings.imagePlacement,
        ) { ReaderSettings.imagePlacement = it }
        s.rowImageScale.bindSegmented(
            ctx, getString(R.string.setting_image_scale),
            listOf(getString(R.string.setting_image_fit) to ImageScaleMode.Fit, getString(R.string.setting_image_fill) to ImageScaleMode.Fill, getString(R.string.setting_image_original) to ImageScaleMode.Original),
            ReaderSettings.imageScaleMode,
        ) { ReaderSettings.imageScaleMode = it }
        s.rowPreloadImage.bindIntSlider(
            getString(R.string.setting_preload_images), 0, 8, ReaderSettings.preloadImageAhead,
        ) { ReaderSettings.preloadImageAhead = it }
        s.rowIllustMix.bindSegmented(
            ctx, getString(R.string.setting_illust_mix),
            listOf(
                getString(R.string.setting_illust_mix_none) to NovelIllustSource.None,
                getString(R.string.setting_illust_mix_followed) to NovelIllustSource.Followed,
                getString(R.string.setting_illust_mix_discover) to NovelIllustSource.Discover,
                getString(R.string.setting_illust_mix_related) to NovelIllustSource.Related,
            ),
            ReaderSettings.illustMixSource,
        ) { ReaderSettings.illustMixSource = it }
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
        // roundToInt 而不是 toInt：currentValue() 存进 MMKV 的是 1.3999999… 这类
        // 单精度近似值，回读换算 progress 得 3.9999…，截断会掉一档——用户设 1.4 行距，
        // 下次打开面板变 1.3，再碰一下滑块就把 1.3 写回去了（#1038）。
        seekBar.progress = (normalized * steps).roundToInt()
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
        val inflater = LayoutInflater.from(ctx)
        options.forEach { (text, value) ->
            val option = ItemReaderSegmentOptionBinding.inflate(inflater, container, false)
            val tv = option.root as TextView
            tv.text = text
            tv.tag = value
            tv.isSelected = value == current
            tv.setOnClickListener {
                onChange(value)
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    child.isSelected = child.tag == value
                }
            }
            container.addView(tv)
        }
    }

    // ---- Theme picker -----------------------------------------------------

    private fun ItemReaderSettingScrollBinding.bindThemePicker(ctx: Context) {
        labelText.text = getString(R.string.setting_theme_preset)
        val inner = itemsContainer
        inner.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        val rings = mutableListOf<Pair<String, View>>()
        ReaderTheme.PRESETS.forEach { theme ->
            val item = ItemReaderThemeSwatchBinding.inflate(inflater, inner, false)
            item.swatchLabel.text = theme.displayName
            // 圆点颜色是数据(每个主题的背景色),tint 到 XML oval 上,不在运行时拼 drawable。
            item.swatchCircle.backgroundTintList = ColorStateList.valueOf(theme.backgroundColor)
            item.swatchRing.isSelected = theme.id == ReaderSettings.themeId
            item.root.setOnClickListener {
                ReaderSettings.themeId = theme.id
                rings.forEach { (id, ring) -> ring.isSelected = id == theme.id }
            }
            rings += theme.id to item.swatchRing
            inner.addView(item.root)
        }
    }

    // ---- Font picker ------------------------------------------------------

    private fun ItemReaderSettingScrollBinding.bindFontPicker(ctx: Context) {
        labelText.text = getString(R.string.setting_font_family)
        val inner = itemsContainer
        inner.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        val chips = mutableListOf<TextView>()
        PresetFonts.BUILT_IN.forEach { font ->
            val item = ItemReaderFontChipBinding.inflate(inflater, inner, false)
            val chip = item.root as TextView
            chip.text = font.displayName
            chip.typeface = TypefaceProvider.resolve(ctx, font.id, 400, false)
            chip.tag = font.id
            chip.isSelected = ReaderSettings.fontId == font.id
            chip.setOnClickListener {
                ReaderSettings.fontId = font.id
                chips.forEach { it.isSelected = it.tag == font.id }
            }
            chips += chip
            inner.addView(chip)
        }
    }

    companion object {
        const val TAG = "ReaderSettingsPanel"
    }
}
