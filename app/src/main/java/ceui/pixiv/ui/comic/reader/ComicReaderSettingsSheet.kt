package ceui.pixiv.ui.comic.reader

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import ceui.lisa.R
import ceui.lisa.databinding.SheetComicReaderSettingsBinding
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.widgets.PixivBottomSheet

class ComicReaderSettingsSheet : PixivBottomSheet(R.layout.sheet_comic_reader_settings) {

    private val binding by viewBinding(SheetComicReaderSettingsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 模式
        binding.comicModeGroup.check(
            if (ComicReaderSettings.readingMode == ComicReaderSettings.ReadingMode.Paged)
                R.id.comic_mode_paged else R.id.comic_mode_webtoon,
        )
        binding.comicModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            ComicReaderSettings.readingMode = if (checkedId == R.id.comic_mode_paged)
                ComicReaderSettings.ReadingMode.Paged else ComicReaderSettings.ReadingMode.Webtoon
        }

        // 方向
        binding.comicDirectionGroup.check(
            if (ComicReaderSettings.pageDirection == ComicReaderSettings.PageDirection.LTR)
                R.id.comic_dir_ltr else R.id.comic_dir_rtl,
        )
        binding.comicDirectionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            ComicReaderSettings.pageDirection = if (checkedId == R.id.comic_dir_ltr)
                ComicReaderSettings.PageDirection.LTR else ComicReaderSettings.PageDirection.RTL
        }

        // Fit
        binding.comicFitGroup.check(
            when (ComicReaderSettings.fitMode) {
                ComicReaderSettings.FitMode.FitWidth -> R.id.comic_fit_width
                ComicReaderSettings.FitMode.FitScreen -> R.id.comic_fit_screen
                ComicReaderSettings.FitMode.FitOriginal -> R.id.comic_fit_original
            },
        )
        binding.comicFitGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            ComicReaderSettings.fitMode = when (checkedId) {
                R.id.comic_fit_width -> ComicReaderSettings.FitMode.FitWidth
                R.id.comic_fit_screen -> ComicReaderSettings.FitMode.FitScreen
                R.id.comic_fit_original -> ComicReaderSettings.FitMode.FitOriginal
                else -> ComicReaderSettings.FitMode.FitWidth
            }
        }

        // 亮度
        binding.comicSysBrightness.isChecked = ComicReaderSettings.useSystemBrightness
        binding.comicBrightnessSeek.isEnabled = !ComicReaderSettings.useSystemBrightness
        binding.comicBrightnessSeek.progress = (ComicReaderSettings.customBrightness * 100).toInt()
        binding.comicSysBrightness.setOnCheckedChangeListener { _, b ->
            ComicReaderSettings.useSystemBrightness = b
            binding.comicBrightnessSeek.isEnabled = !b
        }
        binding.comicBrightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) ComicReaderSettings.customBrightness = (p / 100f).coerceAtLeast(0.01f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })

        // 开关组
        binding.comicKeepScreenOn.isChecked = ComicReaderSettings.keepScreenOn
        binding.comicKeepScreenOn.setOnCheckedChangeListener { _, b -> ComicReaderSettings.keepScreenOn = b }

        binding.comicImmersive.isChecked = ComicReaderSettings.immersive
        binding.comicImmersive.setOnCheckedChangeListener { _, b -> ComicReaderSettings.immersive = b }

        binding.comicShowPageNumber.isChecked = ComicReaderSettings.showPageNumber
        binding.comicShowPageNumber.setOnCheckedChangeListener { _, b -> ComicReaderSettings.showPageNumber = b }

        binding.comicLoadOriginal.isChecked = ComicReaderSettings.loadOriginal
        binding.comicLoadOriginal.setOnCheckedChangeListener { _, b -> ComicReaderSettings.loadOriginal = b }

        binding.comicVolumeFlip.isChecked = ComicReaderSettings.volumeKeyFlip
        binding.comicVolumeFlip.setOnCheckedChangeListener { _, b -> ComicReaderSettings.volumeKeyFlip = b }

        binding.comicTapReversed.isChecked = ComicReaderSettings.tapZoneReversed
        binding.comicTapReversed.setOnCheckedChangeListener { _, b -> ComicReaderSettings.tapZoneReversed = b }

        // 预载页数
        binding.comicPreloadSeek.progress = ComicReaderSettings.preloadAhead
        binding.comicPreloadValue.text = getString(R.string.comic_reader_preload_value, ComicReaderSettings.preloadAhead)
        binding.comicPreloadSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) ComicReaderSettings.preloadAhead = p
                binding.comicPreloadValue.text = getString(R.string.comic_reader_preload_value, p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
    }

    companion object { const val TAG = "ComicReaderSettingsSheet" }
}
