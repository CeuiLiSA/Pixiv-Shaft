package ceui.pixiv.ui.novel.reader.render

import androidx.annotation.ColorInt
import ceui.pixiv.ui.novel.reader.model.HighlightSpan
import ceui.pixiv.ui.novel.reader.model.TextSelection

/**
 * Aggregates everything the renderer needs to paint *on top of* the page text:
 * search hits, saved annotations, active selection, and the current TTS sentence.
 */
data class PageOverlays(
    val searchHits: List<HighlightRange> = emptyList(),
    val annotations: List<HighlightSpan> = emptyList(),
    val selection: TextSelection? = null,
    val ttsActiveRange: IntRange? = null,
    val currentSearchHit: HighlightRange? = null,
) {
    companion object {
        val EMPTY = PageOverlays()
    }
}

data class HighlightRange(
    val absoluteStart: Int,
    val absoluteEnd: Int,
    @ColorInt val color: Int,
    val isCurrent: Boolean = false,
)
