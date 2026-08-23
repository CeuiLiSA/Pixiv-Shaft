package ceui.lisa.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * 侧边抽屉的预测式返回跟手动画(Android 14+ 返回手势有进度回调;更低版本只会走 [close])。
 *
 * DrawerLayout 1.1 自己不懂预测式返回,这里把 [OnBackPressedCallback] 的
 * started / progressed / cancelled / pressed 四个阶段翻译成抽屉 view 的 translationX:
 * 手势拖多少,抽屉就跟着往屏幕边缘缩多少;松手取消弹回原位;提交则从当前位置一口气滑出,
 * 滑完再调 [DrawerLayout.closeDrawer]`(view, false)` 瞬时落实关闭态,并把 translationX 归零,
 * 保证下一次 openDrawer 的布局不受影响。抽屉遮罩由 MainActivity 设成透明,这里不用管。
 *
 * ⚠️ 别把 drawerlayout 升到 1.2.0:它会在抽屉打开时直接向系统 OnBackInvokedDispatcher
 * 注册 PRIORITY_OVERLAY 回调(无跟手动画),压在 AndroidX dispatcher 之上,这套动画就被截胡了。
 */
class DrawerPredictiveBack(
    private val drawerLayout: DrawerLayout,
    private val drawerView: View,
) {
    private val interpolator = DecelerateInterpolator()
    private var animator: ValueAnimator? = null
    private var tracking = false
    private var progress = 0f

    fun onStarted() {
        cancelAnimator()
        tracking = true
        apply(0f)
    }

    fun onProgressed(progress: Float) {
        if (!tracking) return
        apply(progress)
    }

    fun onCancelled() {
        if (!tracking) return
        animateTo(0f) {
            tracking = false
            reset()
        }
    }

    /** 手势提交 / 按键:有跟手态就从当前位置滑出再真正关抽屉,否则走 DrawerLayout 自带的关闭动画。 */
    fun close() {
        if (!tracking) {
            drawerLayout.closeDrawer(drawerView)
            return
        }
        animateTo(1f) {
            drawerLayout.closeDrawer(drawerView, false)
            tracking = false
            reset()
        }
    }

    private fun apply(value: Float) {
        progress = value.coerceIn(0f, 1f)
        drawerView.translationX = drawerView.width * interpolator.getInterpolation(progress) * edgeSign()
    }

    private fun reset() {
        progress = 0f
        drawerView.translationX = 0f
    }

    /** 抽屉贴哪边就往哪边滑出(START 在 RTL 下是右边)。 */
    private fun edgeSign(): Float {
        val gravity = (drawerView.layoutParams as? DrawerLayout.LayoutParams)?.gravity ?: GravityCompat.START
        val absolute = GravityCompat.getAbsoluteGravity(gravity, ViewCompat.getLayoutDirection(drawerView))
        return if (absolute and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT) 1f else -1f
    }

    private fun animateTo(target: Float, onEnd: () -> Unit) {
        cancelAnimator()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = (ANIM_DURATION * kotlin.math.abs(target - progress)).toLong().coerceAtLeast(MIN_ANIM_DURATION)
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    private fun cancelAnimator() {
        animator?.cancel()
        animator = null
    }

    private companion object {
        const val ANIM_DURATION = 220f
        const val MIN_ANIM_DURATION = 80L
    }
}
