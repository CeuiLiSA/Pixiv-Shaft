package ceui.pixiv.ui.novel.reader.model

sealed class ContentToken {
    abstract val sourceStart: Int
    abstract val sourceEnd: Int

    data class Paragraph(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val text: String,
        /** Position in the raw source where [text] actually begins.
         *  May differ from [sourceStart] when leading whitespace was trimmed. */
        val textSourceStart: Int = sourceStart,
        /** Inline markup spans (links, ruby, etc.) with offsets into [text]. */
        val inlineSpans: List<ceui.pixiv.ui.novel.reader.paginate.InlineSpan> = emptyList(),
    ) : ContentToken()

    data class Chapter(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val title: String,
    ) : ContentToken()

    data class PixivImage(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val illustId: Long,
        val pageIndex: Int,
    ) : ContentToken()

    data class UploadedImage(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val imageId: Long,
    ) : ContentToken()

    data class PageBreak(
        override val sourceStart: Int,
        override val sourceEnd: Int,
    ) : ContentToken()

    data class BlankLine(
        override val sourceStart: Int,
        override val sourceEnd: Int,
    ) : ContentToken()
}
