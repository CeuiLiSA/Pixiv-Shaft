package ceui.pixiv.ui.upscale

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * iPhone/国产手机相册风格的主体高亮视图。
 *
 * 显示原图，暗化背景区域，主体保持明亮，
 * 配合呼吸动画让主体看起来"浮起"。
 */
class SubjectHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var originalBitmap: Bitmap? = null
    private var subjectBitmap: Bitmap? = null

    /** Black overlay with subject area punched out (transparent). */
    private var dimOverlay: Bitmap? = null

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var dimAlpha = 0f

    private val breathLow = 0.20f
    private val breathHigh = 0.75f
    private val breathMid = (breathLow + breathHigh) / 2f  // 0.475

    // ── entrance: dim fades in to breath midpoint ────────────────────
    private val entranceAnimator = ValueAnimator.ofFloat(0f, breathMid).apply {
        duration = 600
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            dimAlpha = it.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                breathAnimator.start()
            }
        })
    }

    // ── breathing: pulse from midpoint → high → low → high … ────────
    private val breathAnimator = ValueAnimator.ofFloat(breathMid, breathHigh, breathLow, breathMid).apply {
        duration = 3200
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            dimAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    /**
     * Set the original image and the rembg output (PNG with alpha).
     * The subject alpha channel is used to cut out the dim overlay.
     */
    fun setImages(original: Bitmap, subject: Bitmap) {
        cleanup()
        originalBitmap = original
        subjectBitmap = subject
        buildDimOverlay(subject)
        entranceAnimator.start()
        requestLayout()
        invalidate()
    }

    // ── build the dim overlay bitmap ─────────────────────────────────
    private fun buildDimOverlay(subject: Bitmap) {
        val w = subject.width
        val h = subject.height
        dimOverlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            // Fill with black
            c.drawColor(Color.BLACK)
            // Punch out subject: where subject alpha > 0, clear the black
            val clearPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                isFilterBitmap = true
            }
            c.drawBitmap(subject, 0f, 0f, clearPaint)
        }
    }

    // ── fit-center matrix ────────────────────────────────────────────
    private fun fitCenterMatrix(bw: Int, bh: Int): Matrix {
        val m = Matrix()
        if (width == 0 || height == 0) return m
        val scale = minOf(width.toFloat() / bw, height.toFloat() / bh)
        val dx = (width - bw * scale) / 2f
        val dy = (height - bh * scale) / 2f
        m.setScale(scale, scale)
        m.postTranslate(dx, dy)
        return m
    }

    // ── draw ─────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val orig = originalBitmap ?: return
        val dim = dimOverlay ?: return

        val m = fitCenterMatrix(orig.width, orig.height)

        // 1. Original image at full brightness
        canvas.drawBitmap(orig, m, imagePaint)

        // 2. Dim overlay (background darkened, subject area transparent)
        dimPaint.alpha = (dimAlpha * 255).toInt()
        canvas.drawBitmap(dim, m, dimPaint)
    }

    // ── lifecycle ────────────────────────────────────────────────────
    fun cleanup() {
        entranceAnimator.cancel()
        breathAnimator.cancel()
        dimOverlay?.recycle()
        dimOverlay = null
        // Don't recycle original/subject — managed by caller
    }
}
