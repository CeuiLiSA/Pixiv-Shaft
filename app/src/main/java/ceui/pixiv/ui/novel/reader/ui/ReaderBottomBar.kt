package ceui.pixiv.ui.novel.reader.ui

import android.view.View
import android.widget.SeekBar
import ceui.lisa.databinding.LayoutReaderBottomBarBinding

class ReaderBottomBar(private val binding: LayoutReaderBottomBarBinding) {

    val view: View get() = binding.root

    var onPrevChapter: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null
    var onChaptersClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onThemeToggleClick: (() -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null
    var onMoreClick: (() -> Unit)? = null
    var onSeekStart: (() -> Unit)? = null
    var onSeekChanged: ((pageIndex: Int) -> Unit)? = null
    var onSeekCommit: ((pageIndex: Int) -> Unit)? = null

    private var suppressSeekListener = false

    init {
        binding.btnPrevChapter.setOnClickListener { onPrevChapter?.invoke() }
        binding.btnNextChapter.setOnClickListener { onNextChapter?.invoke() }
        binding.btnChapters.setOnClickListener { onChaptersClick?.invoke() }
        binding.btnSettings.setOnClickListener { onSettingsClick?.invoke() }
        binding.btnThemeToggle.setOnClickListener { onThemeToggleClick?.invoke() }
        binding.btnSearch.setOnClickListener { onSearchClick?.invoke() }
        binding.btnMore.setOnClickListener { onMoreClick?.invoke() }

        binding.skProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressSeekListener) return
                onSeekChanged?.invoke(progress)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                onSeekStart?.invoke()
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                onSeekCommit?.invoke(s.progress)
            }
        })
    }

    fun setProgress(currentPage: Int, totalPages: Int) {
        val totalForBar = (totalPages - 1).coerceAtLeast(0)
        suppressSeekListener = true
        try {
            binding.skProgress.max = totalForBar
            binding.skProgress.progress = currentPage.coerceIn(0, totalForBar)
        } finally {
            suppressSeekListener = false
        }
        binding.txtProgress.text = if (totalPages == 0) {
            binding.root.context.getString(ceui.lisa.R.string.reader_progress_empty)
        } else {
            binding.root.context.getString(ceui.lisa.R.string.reader_progress_format, currentPage + 1, totalPages)
        }
    }

    fun setDarkMode(dark: Boolean) {
        binding.txtThemeToggle.text = binding.root.context.getString(
            if (dark) ceui.lisa.R.string.reader_btn_theme_day else ceui.lisa.R.string.reader_btn_theme_night,
        )
    }
}
