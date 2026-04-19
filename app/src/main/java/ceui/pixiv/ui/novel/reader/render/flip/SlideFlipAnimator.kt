package ceui.pixiv.ui.novel.reader.render.flip

import ceui.pixiv.ui.novel.reader.render.PageView

/**
 * Horizontal slide: both the current and incoming pages translate together, like
 * swiping cards. This is the default for Android readers that want a familiar
 * feel without the 3D simulation cost.
 */
class SlideFlipAnimator : FlipAnimator() {

    override fun onDragProgress(
        progress: Float,
        direction: FlipDirection,
        current: PageView,
        incoming: PageView,
        width: Int,
    ) {
        val signed = progress.coerceIn(0f, 1f) * direction.sign
        current.translationX = -width * signed
        incoming.translationX = width * (1f - progress.coerceIn(0f, 1f)) * direction.sign
    }
}
