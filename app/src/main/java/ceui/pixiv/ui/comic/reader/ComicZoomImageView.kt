package ceui.pixiv.ui.comic.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView：双指缩放 + 双击 zoom + 单指 pan + 边界回弹。
 * 不依赖第三方库，避免引入新 dependency。基于 ImageView 的 Matrix 模式。
 *
 * 不在 reader chrome 之上拦截单击：单击直接 dispatch 给父 View（用于显示/隐藏 chrome）。
 */
class ComicZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var minScale: Float = 1f
    var midScale: Float = 2.5f
    var maxScale: Float = 6f
    var onSingleTap: ((MotionEvent) -> Unit)? = null
    var onDoubleTap: ((MotionEvent) -> Unit)? = null
    var onLongPress: ((MotionEvent) -> Unit)? = null

    /** 当前 view 是否处于"已放大"状态，外部可据此决定要不要拦截 ViewPager2 的横向滑动。 */
    val isZoomed: Boolean get() = currentScale() > minScale * 1.01f

    private val displayMatrix = Matrix()
    private val savedMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { resetMatrix() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    private fun resetMatrix() {
        val drawable = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val intrW = drawable.intrinsicWidth.toFloat()
        val intrH = drawable.intrinsicHeight.toFloat()
        if (intrW <= 0 || intrH <= 0) return

        displayMatrix.reset()
        val scale = when (ComicReaderSettings.fitMode) {
            ComicReaderSettings.FitMode.FitWidth -> viewW / intrW
            ComicReaderSettings.FitMode.FitScreen -> minOf(viewW / intrW, viewH / intrH)
            ComicReaderSettings.FitMode.FitOriginal -> 1f
        }
        displayMatrix.postScale(scale, scale)
        // 将图片居中（左右铺满或留白皆居中），上下与 chrome 相处由父布局 padding 控制。
        val drawnW = intrW * scale
        val drawnH = intrH * scale
        displayMatrix.postTranslate((viewW - drawnW) / 2f, (viewH - drawnH) / 2f)
        imageMatrix = displayMatrix
        minScale = scale
        midScale = (ComicReaderSettings.doubleTapZoomLevel * scale).coerceAtLeast(scale * 1.5f)
        maxScale = (scale * 6f).coerceAtLeast(midScale * 1.5f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            handlePan(event)
        }
        // 已放大或正在缩放时拦截，避免父级 ViewPager 抢手势
        parent?.requestDisallowInterceptTouchEvent(isZoomed || scaleDetector.isInProgress)
        return true
    }

    private val lastTouch = PointF()
    private var dragging = false

    private fun handlePan(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                dragging = true
                savedMatrix.set(displayMatrix)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return
                val dx = event.x - lastTouch.x
                val dy = event.y - lastTouch.y
                lastTouch.set(event.x, event.y)
                displayMatrix.postTranslate(dx, dy)
                clampToBounds()
                imageMatrix = displayMatrix
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }
    }

    private fun clampToBounds() {
        val drawable = drawable ?: return
        val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        displayMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        // 横向：图比容器窄居中，否则限制不留白
        if (rect.width() <= viewW) dx = (viewW - rect.width()) / 2f - rect.left
        else if (rect.left > 0f) dx = -rect.left
        else if (rect.right < viewW) dx = viewW - rect.right
        // 纵向同理
        if (rect.height() <= viewH) dy = (viewH - rect.height()) / 2f - rect.top
        else if (rect.top > 0f) dy = -rect.top
        else if (rect.bottom < viewH) dy = viewH - rect.bottom
        displayMatrix.postTranslate(dx, dy)
    }

    private fun currentScale(): Float {
        displayMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun animateZoom(targetScale: Float, focusX: Float, focusY: Float) {
        val current = currentScale()
        if (current <= 0f) return
        val factor = targetScale / current
        val animator = ValueAnimator.ofFloat(1f, factor).setDuration(220)
        animator.interpolator = DecelerateInterpolator()
        var lastFactor = 1f
        animator.addUpdateListener { va ->
            val v = va.animatedValue as Float
            val step = v / lastFactor
            lastFactor = v
            displayMatrix.postScale(step, step, focusX, focusY)
            clampToBounds()
            imageMatrix = displayMatrix
        }
        animator.start()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = currentScale()
            val target = (current * detector.scaleFactor).coerceIn(minScale * 0.9f, maxScale)
            val effective = target / current
            displayMatrix.postScale(effective, effective, detector.focusX, detector.focusY)
            clampToBounds()
            imageMatrix = displayMatrix
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke(e); return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke(e)
            val current = currentScale()
            val target = if (current > minScale * 1.05f) minScale else midScale
            animateZoom(target, e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke(e)
        }
    }
}
