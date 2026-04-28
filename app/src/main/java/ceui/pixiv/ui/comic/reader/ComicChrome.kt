package ceui.pixiv.ui.comic.reader

import android.view.View
import android.view.Window
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible

/**
 * Mediator：协调顶/底栏显隐，并把"系统状态栏跟随"这件事从 Fragment 里隔离出去。
 * 任何代码想要切显隐，只能调 [toggle] / [setShown]，避免散落在 Fragment 各处的 animate 调用。
 */
class ComicChrome(
    private val topBar: View,
    private val bottomBar: View,
    private val window: Window,
) {
    var shown: Boolean = true
        private set

    var onShownChanged: (Boolean) -> Unit = {}

    fun toggle() = setShown(!shown)

    fun setShown(show: Boolean) {
        if (shown == show) return
        shown = show
        animate(topBar, show)
        animate(bottomBar, show)
        applySystemBars()
        onShownChanged(show)
    }

    /** 沉浸式开关 / chrome 显隐变化时都需要重算系统栏可见性，集中在这里。 */
    fun applySystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (ComicReaderSettings.immersive && !shown) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun animate(view: View, show: Boolean) {
        view.animate()
            .alpha(if (show) 1f else 0f)
            .setDuration(150)
            .withStartAction { if (show) view.isVisible = true }
            .withEndAction { if (!show) view.isVisible = false }
            .start()
    }
}
