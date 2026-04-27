package ceui.pixiv.ui.novel.reader.ui

import android.view.View
import android.widget.SeekBar
import ceui.lisa.databinding.LayoutReaderBottomBarBinding

class ReaderBottomBar(private val binding: LayoutReaderBottomBarBinding) {

    enum class Mode { Paged, VerticalScroll }

    val view: View get() = binding.root

    var onPrevChapter: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null
    var onChaptersClick: (() -> Unit)? = null
    var onSeriesClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onThemeToggleClick: (() -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null
    var onMoreClick: (() -> Unit)? = null
    var onSeekStart: (() -> Unit)? = null
    /** Paged mode: drag changes — pageIndex 0..total-1. */
    var onSeekChanged: ((pageIndex: Int) -> Unit)? = null
    /** Paged mode: drag end — pageIndex 0..total-1. */
    var onSeekCommit: ((pageIndex: Int) -> Unit)? = null
    /** Vertical-scroll mode: drag end — fraction in [0f, 1f]. */
    var onScrollSeekCommit: ((fraction: Float) -> Unit)? = null

    private var suppressSeekListener = false
    private var mode: Mode = Mode.Paged

    init {
        binding.btnPrevChapter.setOnClickListener { onPrevChapter?.invoke() }
        binding.btnNextChapter.setOnClickListener { onNextChapter?.invoke() }
        binding.btnChapters.setOnClickListener { onChaptersClick?.invoke() }
        binding.btnSeries.setOnClickListener { onSeriesClick?.invoke() }
        binding.btnSettings.setOnClickListener { onSettingsClick?.invoke() }
        binding.btnThemeToggle.setOnClickListener { onThemeToggleClick?.invoke() }
        binding.btnSearch.setOnClickListener { onSearchClick?.invoke() }
        binding.btnMore.setOnClickListener { onMoreClick?.invoke() }

        binding.skProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressSeekListener) return
                if (mode == Mode.Paged) onSeekChanged?.invoke(progress)
                // VerticalScroll mode emits only on commit — live drag would jitter the
                // ScrollView and cause re-layout storms.
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                onSeekStart?.invoke()
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                if (mode == Mode.Paged) {
                    onSeekCommit?.invoke(s.progress)
                } else {
                    val max = s.max.coerceAtLeast(1)
                    onScrollSeekCommit?.invoke(s.progress.toFloat() / max)
                }
            }
        })
    }

    fun setProgress(currentPage: Int, totalPages: Int) {
        mode = Mode.Paged
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

    /**
     * Vertical-scroll mode: keep the seekbar in sync with overall scroll progress.
     * Uses a fixed [SCROLL_MAX] resolution so a single pixel of drag corresponds
     * to a sensible fraction even on tall novels. Text shows "NN%".
     */
    fun setScrollProgress(fraction: Float) {
        mode = Mode.VerticalScroll
        val clamped = fraction.coerceIn(0f, 1f)
        suppressSeekListener = true
        try {
            binding.skProgress.max = SCROLL_MAX
            binding.skProgress.progress = (clamped * SCROLL_MAX).toInt()
        } finally {
            suppressSeekListener = false
        }
        val pct = (clamped * 100).toInt().coerceIn(0, 100)
        binding.txtProgress.text = "$pct%"
    }

    /** 当前小说有所属系列时调用 setSeriesVisible(true)，唤起 SeriesListSheet 切换其它单篇。 */
    fun setSeriesVisible(visible: Boolean) {
        binding.btnSeries.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setDarkMode(dark: Boolean) {
        binding.txtThemeToggle.text = binding.root.context.getString(
            if (dark) ceui.lisa.R.string.reader_btn_theme_day else ceui.lisa.R.string.reader_btn_theme_night,
        )
    }

    private companion object {
        // Resolution for the scroll-mode seekbar — fine enough that a 1-pixel
        // drag of the thumb still produces a meaningful jump on a long novel.
        const val SCROLL_MAX = 1000
    }
}
