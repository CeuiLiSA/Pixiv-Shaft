package ceui.pixiv.ui.novel.reader.render.flip

import ceui.pixiv.ui.novel.reader.model.FlipMode

object FlipAnimatorFactory {
    fun create(mode: FlipMode): FlipAnimator = when (mode) {
        FlipMode.Simulation -> SimulationFlipAnimator()
        FlipMode.Cover -> CoverFlipAnimator()
        FlipMode.Slide -> SlideFlipAnimator()
        FlipMode.None -> NoneFlipAnimator()
        FlipMode.Scroll -> NoneFlipAnimator() // scroll mode bypasses NovelReaderView
    }
}
