package ceui.pixiv.ui.novel.reader.model

import android.text.Layout

sealed class PageElement {
    abstract val top: Float
    abstract val bottom: Float
    abstract val absoluteCharStart: Int
    abstract val absoluteCharEnd: Int

    /**
     * One contiguous slice of a paragraph that lives on this page. The
     * `text` field is the raw slice content (plain text, spans stripped);
     * the renderer will re-apply first-line indent and paragraph-gap spans
     * from [paragraphIndex] / [isFirstLineOfParagraph] as needed. This used
     * to carry a [Layout] + line-range, but sharing a Layout object between
     * the paginator's measurement (which reused a single TextView) and the
     * renderer was a footgun — store just the data instead.
     */
    data class Text(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
        val text: CharSequence,
        val paragraphIndex: Int,
        val isFirstLineOfParagraph: Boolean,
        val isLastLineOfParagraph: Boolean,
        val lineCount: Int,
    ) : PageElement()

    data class Chapter(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
        val title: String,
        val layout: Layout,
    ) : PageElement()

    data class Image(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
        val imageType: ImageType,
        val resourceId: Long,
        val pageIndexInIllust: Int,
        val imageUrl: String?,
    ) : PageElement() {
        enum class ImageType { PixivImage, UploadedImage }
    }

    data class Space(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
    ) : PageElement()

    /**
     * `[jump:N]` rendered as a tappable button. [target] is the 1-indexed
     * `[newpage]` segment to navigate to; resolution to a char offset happens
     * in the host fragment so this element stays display-only.
     */
    data class Jump(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
        val target: Int,
    ) : PageElement()
}
