package ceui.lisa.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.doOnPreDraw
import ceui.lisa.view.DragDismissLayout

/**
 * 大图查看页(透明窗口)的自绘转场,配合 [DragDismissLayout] 做出小红书式全屏弹窗观感:
 *  - 进场:内容从点击缩略图的屏幕矩形放大展开到全屏,黑底同步淡入;没带矩形的入口退化为居中放大淡入;
 *  - 回弹:竖向拖拽未过阈值,内容/黑底弹回原状;
 *  - 收场:缩回缩略图矩形(还停在进入页时),否则沿手势方向淡出;动画毕由宿主真正 finish。
 *
 * 内容变换统一以 view 中心为 pivot:目标态只需「缩放后中心对上矩形中心」,进/出共用同一组公式。
 * 缩略图矩形是屏幕坐标,用前换算成本窗口(根布局)坐标 —— 平板 Activity Embedding 下两者不重合。
 */
class ImageViewerTransition(
    private val root: DragDismissLayout,
    private val content: View,
    private val chromeViews: List<View>,
    enterScreenBounds: IntArray?,
) {
    private val background = root.background.mutate().also { root.background = it }
    private val enterBounds: Rect? = enterScreenBounds
        ?.takeIf { it.size == 4 && it[2] > it[0] && it[3] > it[1] }
        ?.let { Rect(it[0], it[1], it[2], it[3]) }
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var animator: ValueAnimator? = null
    private var exiting = false

    /** 不播进场动画(进程重建恢复)时直接铺满黑底。 */
    fun showImmediately() {
        background.alpha = 255
    }

    fun playEnter() {
        root.dragSuspended = true
        background.alpha = 0
        chromeViews.forEach { it.alpha = 0f }
        root.doOnPreDraw {
            val target = boundsInRoot()
            if (target != null && content.width > 0 && target.width() > 0) {
                val startScale = target.width().toFloat() / content.width
                val startTx = target.exactCenterX() - (content.left + content.width / 2f)
                val startTy = target.exactCenterY() - (content.top + content.height / 2f)
                runAnimation(ENTER_DURATION) { f ->
                    content.scaleX = lerp(startScale, 1f, f)
                    content.scaleY = content.scaleX
                    content.translationX = lerp(startTx, 0f, f)
                    content.translationY = lerp(startTy, 0f, f)
                    applyDim(f)
                }
            } else {
                content.alpha = 0f
                runAnimation(ENTER_DURATION) { f ->
                    content.alpha = f
                    content.scaleX = lerp(0.92f, 1f, f)
                    content.scaleY = content.scaleX
                    applyDim(f)
                }
            }
        }
    }

    /** 拖拽跟手阶段:黑底随位移变透明、工具条更快隐掉。内容变换由 [DragDismissLayout] 直接驱动。 */
    fun onDragProgress(fraction: Float) {
        background.alpha = (255 * (1f - fraction * 0.85f)).toInt().coerceIn(0, 255)
        val chromeAlpha = (1f - fraction * 3f).coerceIn(0f, 1f)
        chromeViews.forEach { it.alpha = chromeAlpha }
    }

    /** 竖向拖拽未过阈值,从当前拖拽位置弹回原状。 */
    fun springBack() {
        if (exiting) return
        root.dragSuspended = true
        val startTx = content.translationX
        val startTy = content.translationY
        val startScale = content.scaleX
        val startDim = background.alpha / 255f
        val startChrome = chromeViews.firstOrNull()?.alpha ?: 1f
        runAnimation(RETURN_DURATION) { f ->
            content.translationX = lerp(startTx, 0f, f)
            content.translationY = lerp(startTy, 0f, f)
            content.scaleX = lerp(startScale, 1f, f)
            content.scaleY = content.scaleX
            background.alpha = (255 * lerp(startDim, 1f, f)).toInt().coerceIn(0, 255)
            chromeViews.forEach { it.alpha = lerp(startChrome, 1f, f) }
        }
    }

    /**
     * 收场动画。[backToBounds] 且带过矩形 → 缩回缩略图;否则从当前位置沿 [direction] 继续淡出。
     * 从拖拽中断处接着播,不会跳变。重复调用只生效一次。
     */
    fun playExit(
        backToBounds: Boolean,
        direction: DragDismissLayout.Direction = DragDismissLayout.Direction.DOWN,
        onEnd: () -> Unit,
    ) {
        if (exiting) return
        exiting = true
        root.dragSuspended = true
        val startTx = content.translationX
        val startTy = content.translationY
        val startScale = content.scaleX
        val startAlpha = content.alpha
        val startDim = background.alpha / 255f
        val startChrome = chromeViews.firstOrNull()?.alpha ?: 1f
        val target = if (backToBounds) boundsInRoot() else null
        if (target != null && content.width > 0 && target.width() > 0) {
            val endScale = target.width().toFloat() / content.width
            val endTx = target.exactCenterX() - (content.left + content.width / 2f)
            val endTy = target.exactCenterY() - (content.top + content.height / 2f)
            runAnimation(EXIT_DURATION, onEnd) { f ->
                content.scaleX = lerp(startScale, endScale, f)
                content.scaleY = content.scaleX
                content.translationX = lerp(startTx, endTx, f)
                content.translationY = lerp(startTy, endTy, f)
                background.alpha = (255 * lerp(startDim, 0f, f)).toInt().coerceIn(0, 255)
                chromeViews.forEach { it.alpha = lerp(startChrome, 0f, f) }
            }
        } else {
            val endTy = startTy + content.height * 0.22f * direction.sign
            runAnimation(EXIT_DURATION, onEnd) { f ->
                content.translationY = lerp(startTy, endTy, f)
                content.alpha = lerp(startAlpha, 0f, f)
                background.alpha = (255 * lerp(startDim, 0f, f)).toInt().coerceIn(0, 255)
                chromeViews.forEach { it.alpha = lerp(startChrome, 0f, f) }
            }
        }
    }

    private fun applyDim(fraction: Float) {
        background.alpha = (255 * fraction).toInt().coerceIn(0, 255)
        chromeViews.forEach { it.alpha = fraction }
    }

    /** 屏幕坐标矩形 → 根布局(窗口)坐标;content 是根布局直接子 view,变换公式按同一坐标系算。 */
    private fun boundsInRoot(): Rect? {
        val bounds = enterBounds ?: return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return Rect(bounds).apply { offset(-loc[0], -loc[1]) }
    }

    private fun runAnimation(duration: Long, onEnd: (() -> Unit)? = null, onUpdate: (Float) -> Unit) {
        // 摘掉监听再 cancel:被顶替的动画不允许触发自己的 end 回调(会错误复位 dragSuspended / 提前 finish)
        animator?.removeAllListeners()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = easing
            addUpdateListener { onUpdate(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.dragSuspended = false
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction

    companion object {
        private const val ENTER_DURATION = 280L
        private const val RETURN_DURATION = 220L
        private const val EXIT_DURATION = 240L
    }
}
