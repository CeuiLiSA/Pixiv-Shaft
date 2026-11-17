package ceui.pixiv.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * 收藏烟花微动效组件（轻量原生复刻）。
 * 替代 Lottie 实现点赞烟花（中心弹跳心形 + 扩张圆环 + 飞散彩色彩屑粒子）。
 */
class LikeBurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val heartDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_like_heart_fill)?.mutate()?.also {
        DrawableCompat.setTint(it, Color.parseColor("#FA3A3A"))
    }

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val listeners = mutableListOf<Animator.AnimatorListener>()

    init {
        setWillNotDraw(false)
    }

    fun playAnimation() {
        cancelAnimation()
        isVisible = true
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600L
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    this@LikeBurstView.listeners.forEach { it.onAnimationEnd(animation) }
                }

                override fun onAnimationCancel(animation: Animator) {
                    this@LikeBurstView.listeners.forEach { it.onAnimationCancel(animation) }
                }

                override fun onAnimationStart(animation: Animator) {
                    this@LikeBurstView.listeners.forEach { it.onAnimationStart(animation) }
                }
            })
            start()
        }
    }

    fun cancelAnimation() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    fun addAnimatorListener(listener: Animator.AnimatorListener) {
        listeners.add(listener)
    }

    fun removeAnimatorListener(listener: Animator.AnimatorListener) {
        listeners.remove(listener)
    }

    fun removeAllAnimatorListeners() {
        listeners.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f || progress >= 1f) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 几何中心：原设计贴在 (96, 96)/144 处（即 2/3 处）
        val cx = w * (96f / 144f)
        val cy = h * (96f / 144f)

        // 1. 扩张圆环 (0.02 ~ 0.45 进度)
        if (progress in 0.02f..0.45f) {
            val ringP = (progress - 0.02f) / 0.43f
            val ringRadius = dp(6f) + dp(22f) * ringP
            val strokeW = dp(3.5f) * (1f - ringP) + dp(0.5f)
            val ringAlpha = ((1f - ringP) * 255).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = strokeW
            ringPaint.color = Color.parseColor("#FF8A5C")
            ringPaint.alpha = ringAlpha
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        }

        // 2. 飞散彩屑粒子 (0.08 ~ 0.85 进度)
        if (progress in 0.08f..0.85f) {
            val dotP = (progress - 0.08f) / 0.77f
            val fadeOutAlpha = if (dotP > 0.5f) {
                ((1f - (dotP - 0.5f) / 0.5f) * 255).toInt().coerceIn(0, 255)
            } else {
                255
            }

            // Group 1: 6 颗粉红 (#FF6FA5)
            drawConfettiGroup(
                canvas = canvas,
                cx = cx,
                cy = cy,
                progress = dotP,
                count = 6,
                startAngleDeg = 0f,
                maxDistance = dp(28f),
                baseRadius = dp(2.5f),
                color = Color.parseColor("#FF6FA5"),
                alpha = fadeOutAlpha
            )

            // Group 2: 6 颗琥珀金 (#FFB13D)
            drawConfettiGroup(
                canvas = canvas,
                cx = cx,
                cy = cy,
                progress = dotP,
                count = 6,
                startAngleDeg = 30f,
                maxDistance = dp(24f),
                baseRadius = dp(2.2f),
                color = Color.parseColor("#FFB13D"),
                alpha = fadeOutAlpha
            )

            // Group 3: 4 颗淡紫 (#B388FF)
            drawConfettiGroup(
                canvas = canvas,
                cx = cx,
                cy = cy,
                progress = dotP,
                count = 4,
                startAngleDeg = 15f,
                maxDistance = dp(30f),
                baseRadius = dp(1.8f),
                color = Color.parseColor("#B388FF"),
                alpha = fadeOutAlpha
            )
        }

        // 3. 中心心形弹跳 (0 ~ 0.85 进度)
        heartDrawable?.let { drawable ->
            if (progress < 0.85f) {
                val scale = when {
                    progress < 0.25f -> (progress / 0.25f) * 1.3f
                    progress < 0.45f -> 1.3f - ((progress - 0.25f) / 0.20f) * 0.4f
                    progress < 0.65f -> 0.9f + ((progress - 0.45f) / 0.20f) * 0.15f
                    else -> 1.05f - ((progress - 0.65f) / 0.20f) * 0.05f
                }
                val alpha = if (progress > 0.65f) {
                    ((1f - (progress - 0.65f) / 0.20f) * 255).toInt().coerceIn(0, 255)
                } else {
                    255
                }

                val heartSize = dp(24f) * scale
                val half = heartSize / 2f
                val left = (cx - half).toInt()
                val top = (cy - half).toInt()
                val right = (cx + half).toInt()
                val bottom = (cy + half).toInt()

                drawable.setBounds(left, top, right, bottom)
                drawable.alpha = alpha

                val saveCount = canvas.save()
                val rotation = when {
                    progress < 0.25f -> -15f * (1f - progress / 0.25f)
                    progress < 0.5f -> 5f * (1f - (progress - 0.25f) / 0.25f)
                    else -> 0f
                }
                canvas.rotate(rotation, cx, cy)
                drawable.draw(canvas)
                canvas.restoreToCount(saveCount)
            }
        }
    }

    private fun drawConfettiGroup(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        progress: Float,
        count: Int,
        startAngleDeg: Float,
        maxDistance: Float,
        baseRadius: Float,
        color: Int,
        alpha: Int
    ) {
        val step = 360f / count
        val dist = maxDistance * progress
        val r = (baseRadius * (1f - progress * 0.7f)).coerceAtLeast(dp(0.5f))
        dotPaint.color = color
        dotPaint.alpha = alpha

        for (i in 0 until count) {
            val angle = Math.toRadians((startAngleDeg + i * step).toDouble())
            val x = (cx + dist * cos(angle)).toFloat()
            val y = (cy + dist * sin(angle)).toFloat()
            canvas.drawCircle(x, y, r, dotPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
