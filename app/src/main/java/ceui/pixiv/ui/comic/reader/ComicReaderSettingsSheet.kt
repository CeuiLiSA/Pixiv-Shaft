package ceui.pixiv.ui.comic.reader

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import ceui.lisa.R
import ceui.lisa.databinding.ItemReaderSegmentOptionBinding
import ceui.lisa.databinding.ItemReaderSettingSegmentedBinding
import ceui.lisa.databinding.ItemReaderSettingSliderBinding
import ceui.lisa.databinding.ItemReaderSettingSwitchBinding
import ceui.lisa.databinding.SheetComicReaderSettingsBinding
import ceui.pixiv.utils.letDrawBehindNavBar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

class ComicReaderSettingsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetComicReaderSettingsBinding? = null
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
                val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                sheet?.setBackgroundColor(Color.TRANSPARENT)
                // 让 sheet 背景铺到屏幕底,消除底部 nav bar 那条透明缝/黑条。
                sheet?.letDrawBehindNavBar()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetComicReaderSettingsBinding.inflate(inflater, container, false)
        val ctx = requireContext()

        bindReadingMode(ctx)
        bindDirection(ctx)
        bindFitMode(ctx)
        bindFlipAnim(ctx)
        bindBrightness()
        bindSliders()
        bindSwitches()

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bindReadingMode(ctx: Context) {
        binding.rowReadingMode.bindSegmented(
            ctx, getString(R.string.comic_reader_mode_label),
            listOf(
                getString(R.string.comic_reader_mode_paged) to ComicReaderSettings.ReadingMode.Paged,
                getString(R.string.comic_reader_mode_webtoon) to ComicReaderSettings.ReadingMode.Webtoon,
            ),
            ComicReaderSettings.readingMode,
        ) { ComicReaderSettings.readingMode = it }
    }

    private fun bindDirection(ctx: Context) {
        binding.rowDirection.bindSegmented(
            ctx, getString(R.string.comic_reader_direction_label),
            listOf(
                getString(R.string.comic_reader_dir_ltr) to ComicReaderSettings.PageDirection.LTR,
                getString(R.string.comic_reader_dir_rtl) to ComicReaderSettings.PageDirection.RTL,
            ),
            ComicReaderSettings.pageDirection,
        ) { ComicReaderSettings.pageDirection = it }
    }

    private fun bindFitMode(ctx: Context) {
        binding.rowFitMode.bindSegmented(
            ctx, getString(R.string.comic_reader_fit_label),
            listOf(
                getString(R.string.comic_reader_fit_width) to ComicReaderSettings.FitMode.FitWidth,
                getString(R.string.comic_reader_fit_screen) to ComicReaderSettings.FitMode.FitScreen,
                getString(R.string.comic_reader_fit_original) to ComicReaderSettings.FitMode.FitOriginal,
            ),
            ComicReaderSettings.fitMode,
        ) { ComicReaderSettings.fitMode = it }
    }

    private fun bindFlipAnim(ctx: Context) {
        binding.rowFlipAnim.bindSegmented(
            ctx, getString(R.string.comic_reader_anim_label),
            listOf(
                getString(R.string.comic_reader_anim_slide) to ComicReaderSettings.FlipAnim.Slide,
                getString(R.string.comic_reader_anim_cover) to ComicReaderSettings.FlipAnim.Cover,
                getString(R.string.comic_reader_anim_depth) to ComicReaderSettings.FlipAnim.Depth,
                getString(R.string.comic_reader_anim_flipbook) to ComicReaderSettings.FlipAnim.FlipBook,
            ),
            ComicReaderSettings.flipAnim,
        ) { ComicReaderSettings.flipAnim = it }
    }

    private fun bindBrightness() {
        val toggleCustom = { useSystem: Boolean ->
            val vis = if (useSystem) View.GONE else View.VISIBLE
            binding.rowCustomBrightness.root.visibility = vis
            binding.dividerCustomBrightness.visibility = vis
        }
        binding.rowSysBrightness.bindSwitch(
            getString(R.string.comic_reader_brightness_system), ComicReaderSettings.useSystemBrightness,
        ) {
            ComicReaderSettings.useSystemBrightness = it
            toggleCustom(it)
        }
        binding.rowCustomBrightness.bindFloatSlider(
            getString(R.string.comic_reader_brightness_label), 0.01f, 1f, 99,
            ComicReaderSettings.customBrightness, digits = 2,
        ) { ComicReaderSettings.customBrightness = it }
        toggleCustom(ComicReaderSettings.useSystemBrightness)
    }

    private fun bindSliders() {
        binding.rowWarmFilter.bindFloatSlider(
            getString(R.string.comic_reader_warm_label), 0f, 0.6f, 60,
            ComicReaderSettings.warmFilterStrength, digits = 2,
        ) { ComicReaderSettings.warmFilterStrength = it }

        binding.rowPreload.bindIntSlider(
            getString(R.string.comic_reader_preload_label), 0, 8,
            ComicReaderSettings.preloadAhead,
        ) { ComicReaderSettings.preloadAhead = it }
    }

    private fun bindSwitches() {
        binding.rowKeepScreenOn.bindSwitch(
            getString(R.string.comic_reader_keep_screen_on), ComicReaderSettings.keepScreenOn,
        ) { ComicReaderSettings.keepScreenOn = it }
        binding.rowImmersive.bindSwitch(
            getString(R.string.comic_reader_immersive), ComicReaderSettings.immersive,
        ) { ComicReaderSettings.immersive = it }
        binding.rowShowPageNumber.bindSwitch(
            getString(R.string.comic_reader_show_page_number), ComicReaderSettings.showPageNumber,
        ) { ComicReaderSettings.showPageNumber = it }
        binding.rowLoadOriginal.bindSwitch(
            getString(R.string.comic_reader_load_original), ComicReaderSettings.loadOriginal,
        ) { ComicReaderSettings.loadOriginal = it }
        binding.rowVolumeFlip.bindSwitch(
            getString(R.string.comic_reader_volume_flip), ComicReaderSettings.volumeKeyFlip,
        ) { ComicReaderSettings.volumeKeyFlip = it }
        binding.rowTapReversed.bindSwitch(
            getString(R.string.comic_reader_tap_reversed), ComicReaderSettings.tapZoneReversed,
        ) { ComicReaderSettings.tapZoneReversed = it }
    }

    // ---- Item binders (same MD3-E pattern as novel ReaderSettingsPanel) -----

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

    private fun ItemReaderSettingSwitchBinding.bindSwitch(
        label: String,
        current: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        labelText.text = label
        switchControl.isChecked = current
        switchControl.setOnCheckedChangeListener { _, v -> onChange(v) }
    }

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
        // roundToInt 理由见 ReaderSettingsPanel.bindFloatSlider：截断会让 1.4 档存取
        // 往返后掉回 1.3（#1038）。
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

    companion object { const val TAG = "ComicReaderSettingsSheet" }
}
