package ceui.pixiv.ui.comic.reader

import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * 把"亮度 / 保持屏幕常亮 / 暖色滤镜"三件 window/view 级开销集中起来。
 * Fragment 只调 [apply]，这样设置变更时无需关心具体哪些字段需要重写。
 */
class ComicWindowController(
    private val window: Window,
    private val rootView: View,
    private val warmOverlay: View,
) {
    fun apply() {
        rootView.keepScreenOn = ComicReaderSettings.keepScreenOn

        val lp = window.attributes
        lp.screenBrightness = if (ComicReaderSettings.useSystemBrightness) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            ComicReaderSettings.customBrightness.coerceIn(0.01f, 1f)
        }
        window.attributes = lp

        rootView.setBackgroundColor(
            if (ComicReaderSettings.backgroundDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        )
        warmOverlay.alpha = ComicReaderSettings.warmFilterStrength.coerceIn(0f, 0.6f)
    }
}
