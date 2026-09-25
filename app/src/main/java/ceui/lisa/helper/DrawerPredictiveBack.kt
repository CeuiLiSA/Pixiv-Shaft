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
 * DrawerLayout 1.1 自己不懂预测式返回,这里把 `OnBackPressedCallback` 的
 * started / progressed / cancelled / pressed 四个阶段翻译成抽屉 view 的 translationX:
 * 手势拖多少,抽屉就跟着往屏幕边缘缩多少;松手取消弹回原位;提交则从当前位置一口气滑出,
 * 滑完再调 [DrawerLayout.closeDrawer]`(view, false)` 瞬时落实关闭态,并把 translationX 归零,
 * 保证下一次 openDrawer 的布局不受影响。抽屉遮罩由 MainActivity 设成透明,这里不用管。
 *
 * ⚠️ 别把 drawerlayout 升到 1.2.0:它会在抽屉打开时直接向系统 OnBackInvokedDispatcher
 * 注册 PRIORITY_OVERLAY 回调(无跟手动画),压在 AndroidX dispatcher 之上,这套动画就被截胡了。
 *
 * ## 兜底:拿不到进度时不跟手
 *
 * 某些 ROM 在「未 primed」状态下只投递 started + 恰好 3 个 progress = 0.0 的 stub,逐帧数据
 * 一概没有 —— 此时任何跟手动画都无从驱动。跟手只是表现手段、不是目的,所以这类手势退化成
 * 固定比例的预测返回预览:[onStarted] 带 fallback = true 时,若 [FALLBACK_DELAY_MS] 内
 * 一个真实进度都没等到,就滑到 [FALLBACK_PROGRESS],并不再理会后续的 0 进度样本。
 *
 * 先等再滑,是因为调用方的「未 primed」只是启发式:正常设备冷启动后的首个手势同样会被判进
 * 兜底,而它的真实进度几十毫秒内就到。等这一小段,正常设备就完全看不到兜底;真收不到进度的
 * ROM 只是晚这么一点点才出反馈。滑动途中(或之后)收到 progress > 0 也会立刻切回逐帧跟手。
 */
class DrawerPredictiveBack(
    private val drawerLayout: DrawerLayout,
    private val drawerView: View,
) {
    private val interpolator = DecelerateInterpolator()
    private var animator: ValueAnimator? = null
    private var tracking = false
    private var progress = 0f

    /** 本场手势是否在走「固定比例」的兜底(ROM 只投递 stub 时)。真进度到手就作废。 */
    private var fallback = false

    /**
     * 手势开始。
     *
     * @param fallback 这一场手势是否很可能拿不到有效进度(ROM 未 primed,见类注释)。为 true 时
     *   [FALLBACK_DELAY_MS] 内没等到真实进度就推到 [FALLBACK_PROGRESS],且不再被 progress = 0
     *   的样本拉回;任何时候收到 progress > 0 都会切回跟手。
     */
    fun onStarted(fallback: Boolean = false) {
        cancelAnimator()
        tracking = true
        this.fallback = fallback
        apply(0f)
        if (fallback) {
            // 延迟启动:期间真进度一到,onProgressed 会把这个还没开始的动画取消掉。
            // 从 0 滑到固定值而不是瞬移,是更自然的「预览」观感。
            animateTo(FALLBACK_PROGRESS, startDelay = FALLBACK_DELAY_MS)
        }
    }

    fun onProgressed(progress: Float) {
        if (!tracking) return
        if (progress > EPSILON) {
            // 真进度到手 —— 兜底(如果有)立刻作废,回到逐帧跟手。
            // 还在等待 / 正往 FALLBACK_PROGRESS 滑的动画必须一起停掉,否则它逐帧覆盖真实进度,
            // 滑完还停在固定值,下一个样本再猛地拉回手指位置。
            if (fallback) {
                fallback = false
                cancelAnimator()
            }
            apply(progress)
            return
        }
        if (fallback) return   // 兜底期间:stub 的 0 样本不能把固定值拉回去
        apply(progress)
    }

    fun onCancelled() {
        if (!tracking) return
        animateTo(0f) { finishTracking() }
    }

    /** 手势提交 / 按键:有跟手态就从当前位置滑出再真正关抽屉,否则走 DrawerLayout 自带的关闭动画。 */
    fun close() {
        if (!tracking) {
            drawerLayout.closeDrawer(drawerView)
            return
        }
        animateTo(1f) {
            drawerLayout.closeDrawer(drawerView, false)
            finishTracking()
        }
    }

    /** 收尾:清掉整场手势的状态(含兜底标记),并把 translationX 归零。 */
    private fun finishTracking() {
        tracking = false
        fallback = false
        reset()
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

    private fun animateTo(target: Float, startDelay: Long = 0L, onEnd: (() -> Unit)? = null) {
        cancelAnimator()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = (ANIM_DURATION * kotlin.math.abs(target - progress)).toLong().coerceAtLeast(MIN_ANIM_DURATION)
            this.startDelay = startDelay
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd?.invoke()
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

        /** 兜底比例:拿不到进度时固定把抽屉推到这里。纯观感参数,按手感调。 */
        const val FALLBACK_PROGRESS = 0.35f

        /**
         * 兜底前先等真实进度的时间。实测已 primed 时首个真实进度约在手势开始后 56ms 到达,
         * 取其两倍左右留余量;再长,收不到进度的 ROM 上反馈就显得迟钝。
         */
        const val FALLBACK_DELAY_MS = 120L

        /** 小于它就算「没有有效进度」。 */
        const val EPSILON = 1e-3f
    }
}
