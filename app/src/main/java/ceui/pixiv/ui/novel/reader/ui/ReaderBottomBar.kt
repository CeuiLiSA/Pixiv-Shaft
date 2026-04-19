package ceui.pixiv.ui.novel.reader.ui

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import ceui.lisa.R

/**
 * Bottom chrome: progress seekbar + quick actions row (目录 / 设置 / 夜间 /
 * 搜索 / 更多). Host wires behavior through the exposed lambda properties so we
 * stay decoupled from Fragment state.
 */
class ReaderBottomBar(private val rootView: View) {

    private val btnPrevChapter = rootView.findViewById<ImageButton>(R.id.btn_prev_chapter)
    private val btnNextChapter = rootView.findViewById<ImageButton>(R.id.btn_next_chapter)
    private val seekBar = rootView.findViewById<SeekBar>(R.id.sk_progress)
    private val txtProgress = rootView.findViewById<TextView>(R.id.txt_progress)
    private val btnChapters = rootView.findViewById<View>(R.id.btn_chapters)
    private val btnSettings = rootView.findViewById<View>(R.id.btn_settings)
    private val btnThemeToggle = rootView.findViewById<View>(R.id.btn_theme_toggle)
    private val btnSearch = rootView.findViewById<View>(R.id.btn_search)
    private val btnMore = rootView.findViewById<View>(R.id.btn_more)
    private val imgThemeToggle = rootView.findViewById<ImageView>(R.id.img_theme_toggle)
    private val txtThemeToggle = rootView.findViewById<TextView>(R.id.txt_theme_toggle)

    val view: View get() = rootView

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
        btnPrevChapter.setOnClickListener { onPrevChapter?.invoke() }
        btnNextChapter.setOnClickListener { onNextChapter?.invoke() }
        btnChapters.setOnClickListener { onChaptersClick?.invoke() }
        btnSettings.setOnClickListener { onSettingsClick?.invoke() }
        btnThemeToggle.setOnClickListener { onThemeToggleClick?.invoke() }
        btnSearch.setOnClickListener { onSearchClick?.invoke() }
        btnMore.setOnClickListener { onMoreClick?.invoke() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
            seekBar.max = totalForBar
            seekBar.progress = currentPage.coerceIn(0, totalForBar)
        } finally {
            suppressSeekListener = false
        }
        txtProgress.text = if (totalPages == 0) "-- / --" else "${currentPage + 1} / $totalPages"
    }

    fun setDarkMode(dark: Boolean) {
        txtThemeToggle.text = if (dark) "日间" else "夜间"
    }
}
