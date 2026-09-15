package ceui.pixiv.ui.comic.reader

import android.view.View
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager

/**
 * 把"亮度 / 保持屏幕常亮 / 暖色滤镜"三件 window/view 级开销集中起来。
 * Fragment 只调 [apply]，这样设置变更时无需关心具体哪些字段需要重写。
 */
class ComicWindowController(
    private val activity: Activity,
    private val rootView: View,
    private val warmOverlay: View,
    savedState: Bundle? = null,
) {
    private val window get() = activity.window
    private val originalOrientation = savedState?.getInt(KEY_ORIGINAL_ORIENTATION)
        ?: activity.requestedOrientation
    private var orientationOverridden = savedState?.getBoolean(KEY_ORIENTATION_OVERRIDDEN) ?: false

    fun applyImageOrientation(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // 接近正方形的图片不改变方向，避免混排时在横竖屏之间反复切换。
        val target = when {
            width.toLong() * 10 >= height.toLong() * 11 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            height.toLong() * 10 >= width.toLong() * 11 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> return
        }
        orientationOverridden = true
        if (activity.requestedOrientation != target) activity.requestedOrientation = target
    }

    fun restoreOrientation() {
        if (!orientationOverridden) return
        orientationOverridden = false
        activity.requestedOrientation = originalOrientation
    }

    fun saveState(outState: Bundle) {
        outState.putInt(KEY_ORIGINAL_ORIENTATION, originalOrientation)
        outState.putBoolean(KEY_ORIENTATION_OVERRIDDEN, orientationOverridden)
    }

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

    private companion object {
        const val KEY_ORIGINAL_ORIENTATION = "comic_original_orientation"
        const val KEY_ORIENTATION_OVERRIDDEN = "comic_orientation_overridden"
    }
}
