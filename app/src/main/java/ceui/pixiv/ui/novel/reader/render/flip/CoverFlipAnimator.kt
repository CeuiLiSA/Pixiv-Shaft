package ceui.pixiv.ui.novel.reader.render.flip

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import ceui.pixiv.ui.novel.reader.render.PageView

/**
 * "Cover" mode: the current page stays put and the incoming page slides IN on top
 * from the right edge (forward) or left edge (backward). Models iBooks / WeRead
 * style with a subtle shadow bleeding from the incoming page's trailing edge.
 */
class CoverFlipAnimator : FlipAnimator() {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowWidthPx = 24f

    override fun onDragStart(current: PageView, incoming: PageView, direction: FlipDirection) {
        super.onDragStart(current, incoming, direction)
        // Incoming is on top; current stays anchored.
        current.bringToFront() // no-op if already on top
        incoming.bringToFront()
    }

    override fun onDragProgress(
        progress: Float,
        direction: FlipDirection,
        current: PageView,
        incoming: PageView,
        width: Int,
    ) {
        current.translationX = 0f
        val p = progress.coerceIn(0f, 1f)
        incoming.translationX = width * (1f - p) * direction.sign
    }

    /**
     * Optional per-frame overlay used by the host to paint a drop-shadow on the
     * trailing edge of the incoming page. We don't enable [overridesCanvas] so
     * the host only calls this when it decides to.
     */
    fun paintShadow(canvas: Canvas, incomingLeftOnScreen: Float, height: Int, direction: FlipDirection) {
        val gradient = if (direction == FlipDirection.Forward) {
            LinearGradient(
                incomingLeftOnScreen - shadowWidthPx, 0f,
                incomingLeftOnScreen, 0f,
                intArrayOf(Color.TRANSPARENT, 0x66000000),
                null, Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                incomingLeftOnScreen, 0f,
                incomingLeftOnScreen + shadowWidthPx, 0f,
                intArrayOf(0x66000000, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
        }
        shadowPaint.shader = gradient
        val rect = if (direction == FlipDirection.Forward) {
            RectF(incomingLeftOnScreen - shadowWidthPx, 0f, incomingLeftOnScreen, height.toFloat())
        } else {
            RectF(incomingLeftOnScreen, 0f, incomingLeftOnScreen + shadowWidthPx, height.toFloat())
        }
        canvas.drawRect(rect, shadowPaint)
        shadowPaint.shader = null
    }
}
