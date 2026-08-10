package ceui.pixiv.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import ceui.lisa.R
import ceui.pixiv.widget.telegram.SpoilerEffect2

/**
 * RecyclerView-friendly host for Telegram Android's original [SpoilerEffect2].
 *
 * All instances draw the same GPU-generated TextureView surface. This view only clips that shared
 * texture to its card and participates in Telegram's attach/detach bookkeeping.
 */
class SpoilerParticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private val clipBounds = RectF()
    private var spoilerEffect: SpoilerEffect2? = null
    private var requestedRunning = true
    private var aggregatedVisible = false

    private var cornerRadius: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            rebuildClipPath()
            invalidate()
        }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.SpoilerParticleView, defStyleAttr, 0)
            .apply {
                cornerRadius = getDimension(
                    R.styleable.SpoilerParticleView_spv_cornerRadius,
                    0f,
                )
                recycle()
            }
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Called by the feed renderer so prefetched and recycled holders do no animation work. */
    fun setParticleAnimationRunning(running: Boolean) {
        if (requestedRunning == running) return
        requestedRunning = running
        updateAttachment()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val effect = spoilerEffect ?: return
        if (width <= 0 || height <= 0) return

        val saveCount = canvas.save()
        if (cornerRadius > 0f) canvas.clipPath(clipPath)
        effect.draw(canvas, this, width, height)
        canvas.restoreToCount(saveCount)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildClipPath()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAttachment()
    }

    override fun onDetachedFromWindow() {
        detachEffect()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateAttachment()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAttachment()
    }

    private fun updateAttachment() {
        val shouldAttach = requestedRunning &&
            isAttachedToWindow &&
            aggregatedVisible &&
            windowVisibility == VISIBLE &&
            visibility == VISIBLE

        if (shouldAttach) {
            if (spoilerEffect == null) {
                spoilerEffect = SpoilerEffect2.getInstance(this)
                invalidate()
            }
        } else {
            detachEffect()
        }
    }

    private fun detachEffect() {
        spoilerEffect?.detach(this)
        spoilerEffect = null
    }

    private fun rebuildClipPath() {
        clipBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.rewind()
        if (cornerRadius > 0f) {
            clipPath.addRoundRect(clipBounds, cornerRadius, cornerRadius, Path.Direction.CW)
        }
    }
}
