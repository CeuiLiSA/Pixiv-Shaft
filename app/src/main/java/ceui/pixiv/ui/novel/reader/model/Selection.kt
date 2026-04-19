package ceui.pixiv.ui.novel.reader.model

data class TextSelection(
    val absoluteStart: Int,
    val absoluteEnd: Int,
    val text: String,
) {
    val isCollapsed: Boolean get() = absoluteStart == absoluteEnd
    val length: Int get() = absoluteEnd - absoluteStart

    companion object {
        val EMPTY = TextSelection(0, 0, "")
    }
}

data class HighlightSpan(
    val annotationId: Long,
    val absoluteStart: Int,
    val absoluteEnd: Int,
    val color: Int,
    val hasNote: Boolean,
)
