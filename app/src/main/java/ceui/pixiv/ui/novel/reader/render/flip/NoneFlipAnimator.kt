package ceui.pixiv.ui.novel.reader.render.flip

import ceui.pixiv.ui.novel.reader.render.PageView

/**
 * Instantly swaps pages. The drag progress still animates translation so the user
 * has visual feedback during the gesture, but the "settle" animation takes 0ms —
 * the host skips the interpolation and commits or cancels immediately.
 */
class NoneFlipAnimator : FlipAnimator() {

    override val durationMs: Long = 0L

    override fun onDragProgress(
        progress: Float,
        direction: FlipDirection,
        current: PageView,
        incoming: PageView,
        width: Int,
    ) {
        val committed = progress >= 0.5f
        if (committed) {
            current.translationX = -width.toFloat() * direction.sign
            incoming.translationX = 0f
        } else {
            current.translationX = 0f
            incoming.translationX = width.toFloat() * direction.sign
        }
    }
}
