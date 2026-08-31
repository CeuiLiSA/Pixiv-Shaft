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
 * ⚠️ 不能只靠 `setOnApplyWindowInsetsListener`：本 View 是 content 的**第二个**子节点，
 * 排在它前面的宿主布局（DrawerLayout 的 fitsSystemWindows、toolbar 上返回 `CONSUMED` 的
 * listener）会把 insets 消费掉，`ViewGroup.dispatchApplyWindowInsets` 遇到 consumed 就
 * break，回调永远到不了这里。所以挂上窗口时直接读 root window insets 兜底，
 * 尺寸变化（旋转 / 折叠屏展开）时再读一次；listener 保留给「没人消费」的正常路径。
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

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyStatusBarInset(insets)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.getRootWindowInsets(this)?.let(::applyStatusBarInset)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) ViewCompat.getRootWindowInsets(this)?.let(::applyStatusBarInset)
    }

    private fun applyStatusBarInset(insets: WindowInsetsCompat) {
        updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
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
