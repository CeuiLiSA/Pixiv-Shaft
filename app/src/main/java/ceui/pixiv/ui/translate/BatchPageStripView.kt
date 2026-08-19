package ceui.pixiv.ui.translate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * 「翻译整部」悬浮小窗里的逐页分段进度条。
 *
 * - 每一页一个圆角小段:已完成 → 实心紫蓝渐变;当前页 → 按阶段百分比从左往右填充并做呼吸;
 *   未开始 → 暗白
 * - 页数多到每段不足 [MIN_SEGMENT_PX] 时退化成一根连续渐变条(当前进度 = 已完成页 + 当前页内占比)
 * - 渐变 shader 横跨整个 View 宽度,所以不管多少段,颜色都是一条连续的紫 → 蓝
 */
class BatchPageStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var total = 0
    private var done = 0
    /** 当前页内阶段占比 0..1;null = 阶段没有百分比(indeterminate,当前页画成呼吸的薄填充) */
    private var currentFraction: Float? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val rect = RectF()
    private var shader: LinearGradient? = null

    /** 当前页段的呼吸 alpha,0.45 ↔ 1.0 往返 */
    private var breath = 1f
    private val breathAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
        duration = 900L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            breath = it.animatedValue as Float
            invalidate()
        }
    }

    fun setProgress(total: Int, done: Int, currentFraction: Float?) {
        this.total = total.coerceAtLeast(0)
        this.done = done.coerceIn(0, this.total)
        this.currentFraction = currentFraction?.coerceIn(0f, 1f)
        syncBreath()
        invalidate()
    }

    private fun syncBreath() {
        val shouldBreathe = isAttachedToWindow && isShown && total > 0 && done < total
        if (shouldBreathe && !breathAnimator.isStarted) breathAnimator.start()
        if (!shouldBreathe && breathAnimator.isStarted) {
            breathAnimator.cancel()
            breath = 1f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncBreath()
    }

    override fun onDetachedFromWindow() {
        breathAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncBreath()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(COLOR_START, COLOR_MID, COLOR_END),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f
        fillPaint.shader = shader

        if (total <= 0) {
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            return
        }

        val gap = GAP_DP * resources.displayMetrics.density
        val segW = (w - gap * (total - 1)) / total
        if (segW < MIN_SEGMENT_PX * resources.displayMetrics.density) {
            drawContinuous(canvas, w, h, radius)
            return
        }

        for (i in 0 until total) {
            val left = i * (segW + gap)
            rect.set(left, 0f, left + segW, h)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            when {
                i < done -> {
                    fillPaint.alpha = 255
                    canvas.drawRoundRect(rect, radius, radius, fillPaint)
                }
                i == done -> {
                    val f = currentFraction
                    fillPaint.alpha = (255 * breath).toInt()
                    if (f == null) {
                        // 没有百分比:整段用呼吸的半透明填充表示「在忙」
                        canvas.drawRoundRect(rect, radius, radius, fillPaint)
                    } else {
                        val fillW = (segW * f).coerceAtLeast(h) // 至少画出一个圆点
                        rect.set(left, 0f, left + fillW, h)
                        canvas.drawRoundRect(rect, radius, radius, fillPaint)
                    }
                }
            }
        }
    }

    private fun drawContinuous(canvas: Canvas, w: Float, h: Float, radius: Float) {
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        val inPage = currentFraction ?: 0f
        val overall = ((done + inPage) / total).coerceIn(0f, 1f)
        val fillW = (w * overall).coerceAtLeast(h)
        fillPaint.alpha = 255
        rect.set(0f, 0f, fillW, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        if (done < total) {
            // 条头上叠一个呼吸的亮点,表示还在跑
            fillPaint.alpha = (255 * breath).toInt()
            rect.set((fillW - h * 2).coerceAtLeast(0f), 0f, fillW, h)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
    }

    companion object {
        private const val GAP_DP = 2f
        private const val MIN_SEGMENT_PX = 5f
        private const val COLOR_START = 0xFF6C5CE7.toInt()
        private const val COLOR_MID = 0xFF5B6AE0.toInt()
        private const val COLOR_END = 0xFF4FACFE.toInt()
    }
}
