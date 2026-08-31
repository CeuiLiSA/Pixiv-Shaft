package ceui.pixiv.banner

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Top-of-screen overlay container that hosts the currently-presenting
 * banner view. Installed once per Activity by [BannerHostInstaller] as a
 * sibling of `android.R.id.content`.
 *
 * Status-bar inset handling: pads itself with the status-bar inset so the
 * banner sits below the status bar regardless of binder.
 *
 * Touch pass-through: forwards any touch outside the banner child back to
 * the underlying Activity content so taps on screen areas not covered by
 * the banner still reach the underlying UI.
 */
class BannerHost(context: Context) : FrameLayout(context) {

    init {
        fitsSystemWindows = false
        clipChildren = false
        clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = statusBars.top)
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (childCount == 0) return false
        val child = getChildAt(0)
        val y = ev.y
        val inChild = y >= child.top + child.translationY &&
            y <= child.bottom + child.translationY
        return if (inChild) super.onInterceptTouchEvent(ev) else false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
