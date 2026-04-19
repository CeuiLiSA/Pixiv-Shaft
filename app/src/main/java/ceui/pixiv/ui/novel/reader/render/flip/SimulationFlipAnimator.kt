package ceui.pixiv.ui.novel.reader.render.flip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import ceui.pixiv.ui.novel.reader.render.PageView
import kotlin.math.abs

/**
 * Page-curl simulation. This implementation uses a two-layer approach that is
 * cheap and visually convincing for portrait reading:
 *
 *  - The **static layer** is the incoming page bitmap, fully opaque.
 *  - The **curl layer** is the current page bitmap drawn on top, with its
 *    trailing edge peeled away: we translate the current bitmap and clip it
 *    with a diagonal cut, then overlay a subtle gradient shadow to sell depth.
 *
 * For a full 3D bend (mesh-based) we'd need OpenGL — that's a follow-up when the
 * paginator / gesture / animator stack is stable.
 */
class SimulationFlipAnimator : FlipAnimator() {

    override val overridesCanvas: Boolean = true

    private val curlPath = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 220 }
    private val matrix = Matrix()

    override fun onDragProgress(
        progress: Float,
        direction: FlipDirection,
        current: PageView,
        incoming: PageView,
        width: Int,
    ) {
        // When overridesCanvas is true, host hides children and calls drawOverlay.
        // We still zero transforms so other modes switching in don't inherit them.
        current.translationX = 0f
        incoming.translationX = 0f
    }

    override fun drawOverlay(
        canvas: Canvas,
        progress: Float,
        direction: FlipDirection,
        currentBitmap: Bitmap?,
        incomingBitmap: Bitmap?,
        width: Int,
        height: Int,
    ) {
        val p = progress.coerceIn(0f, 1f)

        // Paint the incoming page as the base layer.
        if (incomingBitmap != null && !incomingBitmap.isRecycled) {
            canvas.drawBitmap(incomingBitmap, 0f, 0f, null)
        }
        if (currentBitmap == null || currentBitmap.isRecycled) return

        // The curl pivots around the trailing edge. For Forward, trailing edge is
        // the right edge and the page peels to the left.
        val offset = width * p * direction.sign
        val save = canvas.save()
        try {
            // Translate the current bitmap along the drag axis.
            canvas.translate(-offset, 0f)
            // Clip a diagonal peel: the further we drag, the more the trailing
            // corner folds in toward the centre, giving the illusion of depth.
            val peel = width * 0.12f * p
            curlPath.reset()
            if (direction == FlipDirection.Forward) {
                curlPath.moveTo(0f, 0f)
                curlPath.lineTo(width.toFloat() - peel, 0f)
                curlPath.lineTo(width.toFloat(), peel)
                curlPath.lineTo(width.toFloat(), height.toFloat())
                curlPath.lineTo(0f, height.toFloat())
                curlPath.close()
            } else {
                curlPath.moveTo(peel, 0f)
                curlPath.lineTo(width.toFloat(), 0f)
                curlPath.lineTo(width.toFloat(), height.toFloat())
                curlPath.lineTo(0f, height.toFloat())
                curlPath.lineTo(0f, peel)
                curlPath.close()
            }
            canvas.clipPath(curlPath)
            canvas.drawBitmap(currentBitmap, 0f, 0f, null)
        } finally {
            canvas.restoreToCount(save)
        }

        // Shadow along the peel seam.
        val seamX = if (direction == FlipDirection.Forward) {
            width - offset - (width * 0.04f)
        } else {
            -offset + (width * 0.04f)
        }
        val shadowWidth = width * 0.06f * (0.3f + p * 0.7f)
        val gradient = LinearGradient(
            seamX - shadowWidth, 0f, seamX + shadowWidth, 0f,
            intArrayOf(0x00000000, 0x55000000, 0x00000000),
            null, Shader.TileMode.CLAMP,
        )
        shadowPaint.shader = gradient
        canvas.drawRect(seamX - shadowWidth, 0f, seamX + shadowWidth, height.toFloat(), shadowPaint)
        shadowPaint.shader = null
    }

    override fun onReset(prev: PageView, current: PageView, next: PageView) {
        super.onReset(prev, current, next)
        matrix.reset()
    }
}
