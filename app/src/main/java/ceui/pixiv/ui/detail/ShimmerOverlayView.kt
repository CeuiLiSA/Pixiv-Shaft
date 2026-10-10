package ceui.pixiv.ui.detail

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.VisibleForTesting
import ceui.lisa.R

/**
 * 微光扫动叠层。
 *
 * - `app:shimmerLoop="true"`（默认）：无限循环，给加载中的骨架块用；
 * - `app:shimmerLoop="false"`：每次挂上窗口只扫一遍就停，给已加载内容上的装饰用
 *   （详情页数据卡等）。装饰若无限循环，卡片露出一角整个窗口就会 120fps 持续重绘。
 *
 * 只在「已挂窗口且聚合可见」时跑动画：页面退后台、被 GONE 掉时停下，不空转。
 */
class ShimmerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerColor = 0x0F8B7BDB.toInt()
    private val cornerRadius = 20f * resources.displayMetrics.density
    private val loop: Boolean

    /** 光带左缘位置，以 View 宽度为单位；光带宽 0.5，落在 (-0.5, 1) 之外时完全不可见。 */
    private var shimmerOffset = OFFSET_HIDDEN_START

    private var aggregatedVisible = false

    /** 单次模式下本次挂窗口是否已扫过（含被中途打断），detach 时复位。 */
    private var sweptThisAttach = false

    private val animator: ValueAnimator

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ShimmerOverlayView, defStyleAttr, 0)
        loop = a.getBoolean(R.styleable.ShimmerOverlayView_shimmerLoop, true)
        a.recycle()

        animator = if (loop) {
            ValueAnimator.ofFloat(OFFSET_HIDDEN_START, OFFSET_HIDDEN_END).apply {
                duration = LOOP_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
            }
        } else {
            // 只取光带真正可见的那一段，并按比例缩短时长，扫速与循环模式一致
            ValueAnimator.ofFloat(OFFSET_VISIBLE_START, OFFSET_VISIBLE_END).apply {
                duration = (LOOP_DURATION_MS * (OFFSET_VISIBLE_END - OFFSET_VISIBLE_START) /
                    (OFFSET_HIDDEN_END - OFFSET_HIDDEN_START)).toLong()
            }
        }
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener {
            shimmerOffset = it.animatedValue as Float
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // 正常结束与 cancel 都会走到这里：光带停在不可见位置，避免被打断时冻在半路
                shimmerOffset = if (loop) OFFSET_HIDDEN_START else OFFSET_HIDDEN_END
                invalidate()
            }
        })

        // 圆角裁剪交给 RenderNode 的 outline，不在每帧 onDraw 里 clipPath
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 渐变按光带宽度建一次，绘制时平移画布，而不是每帧 new LinearGradient
        shimmerPaint.shader = if (w > 0) {
            LinearGradient(
                0f, 0f, w * BAND_WIDTH, 0f,
                intArrayOf(Color.TRANSPARENT, shimmerColor, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val bandWidth = width * BAND_WIDTH
        val startX = width * shimmerOffset
        if (startX >= width || startX + bandWidth <= 0f) return

        val saveCount = canvas.save()
        canvas.translate(startX, 0f)
        canvas.drawRect(0f, 0f, bandWidth, height.toFloat(), shimmerPaint)
        canvas.restoreToCount(saveCount)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        sweptThisAttach = false
        shimmerOffset = OFFSET_HIDDEN_START
        super.onDetachedFromWindow()
    }

    @VisibleForTesting
    internal val isShimmerRunning: Boolean
        get() = animator.isStarted

    private fun updateAnimation() {
        val shouldRun = isAttachedToWindow && aggregatedVisible
        if (!shouldRun) {
            if (animator.isStarted) animator.cancel()
            return
        }
        if (animator.isStarted) return
        if (loop) {
            animator.start()
        } else if (!sweptThisAttach) {
            sweptThisAttach = true
            animator.start()
        }
    }

    private companion object {
        const val BAND_WIDTH = 0.5f
        const val OFFSET_HIDDEN_START = -1f
        const val OFFSET_HIDDEN_END = 2f
        const val OFFSET_VISIBLE_START = -BAND_WIDTH
        const val OFFSET_VISIBLE_END = 1f
        const val LOOP_DURATION_MS = 4000L
    }
}
