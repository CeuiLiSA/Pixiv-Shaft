package ceui.pixiv.feeds

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/** 一块骨架：矩形 + 自己的圆角（封面 12dp / 文字条 4dp / 头像取半径 / chip 取半高）。 */
class SkeletonBlock(val rect: RectF, val corner: Float)

/**
 * 首屏「骨架图」+ shimmer 发光的**公共引擎**。子类只摆块（[buildBlocks]），怎么画、怎么发光、
 * 何时跑动画全在这里——瀑布流（[FeedStaggeredSkeletonView]）和竖向小说列表
 * （[FeedNovelSkeletonView]）共用同一套。
 *
 * 跑满屏幕刷新率（标准做法，和 Facebook Shimmer / Compose shimmer 一致）——「不重」靠的是
 * **每帧极省**，而不是压帧率：
 *
 * - shimmer = 一条建一次的渐变 shader，每帧只 [Matrix] 平移它再 drawRoundRect。**无 clipPath**
 *   （复杂 path 裁剪会退软件渲染，是最早那版卡顿的真凶）、onDraw 内**零分配**。实测满帧 0 掉帧、
 *   GPU ~4-8ms/帧，全程 GPU 加速。
 * - 只在可见 + 系统允许动画时跑；不可见 / 未 attach / 系统关动画即停，绝不离屏空转。
 *
 * 块的位置**确定性**（不用随机数）→ 零 jitter，尺寸不变就不重算。
 */
abstract class FeedSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    protected val density: Float = resources.displayMetrics.density

    private val blockColor = ContextCompat.getColor(context, R.color.feed_skeleton_block)
    private val highlightColor = ContextCompat.getColor(context, R.color.feed_skeleton_highlight)
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blockColor }
    private val blocks = ArrayList<SkeletonBlock>()

    private var shimmer: LinearGradient? = null
    private var bandWidth = 0f
    private val shimmerMatrix = Matrix()
    private var shimmerFraction = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            shimmerFraction = it.animatedFraction // 用 fraction，避免 animatedValue 每帧装箱
            invalidate()
        }
    }

    /**
     * 摆块：把这一屏要画的骨架块塞进 [out]（已清空）。只在尺寸/参数变化时调用，可以分配对象；
     * 别在这里碰 onDraw 的状态。
     */
    protected abstract fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>)

    /** 子类摆块参数变了（如列数）→ 重算。 */
    protected fun rebuildBlocks() {
        blocks.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        buildBlocks(w, h, blocks)
        bandWidth = w * 0.5f
        shimmer = LinearGradient(
            0f, 0f, bandWidth, 0f,
            intArrayOf(blockColor, highlightColor, blockColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** 摆块小工具：左上角 + 宽高 + 圆角。 */
    protected fun block(left: Float, top: Float, w: Float, h: Float, corner: Float) =
        SkeletonBlock(RectF(left, top, left + w, top + h), corner)

    private fun motionEnabled(): Boolean =
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) != 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBlocks()
    }

    override fun onDraw(canvas: Canvas) {
        if (blocks.isEmpty()) return
        val g = shimmer
        if (g != null && animator.isStarted) {
            // 每帧只平移矩阵：高光条从屏左外扫到屏右外。无分配、无 clip。
            val travel = width + bandWidth
            shimmerMatrix.setTranslate(-bandWidth + travel * shimmerFraction, 0f)
            g.setLocalMatrix(shimmerMatrix)
            blockPaint.shader = g
        } else {
            blockPaint.shader = null // 静态（无障碍关动画时）
            blockPaint.color = blockColor
        }
        for (b in blocks) {
            canvas.drawRoundRect(b.rect, b.corner, b.corner, blockPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        if (animator.isStarted) animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncAnimator()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimator()
    }

    /** 只在真正可见 + 系统允许动画时跑；否则停（静态骨架），避免离屏空转。 */
    private fun syncAnimator() {
        if (isShown && motionEnabled()) {
            if (!animator.isStarted) animator.start()
        } else if (animator.isStarted) {
            animator.cancel()
            invalidate()
        }
    }
}
