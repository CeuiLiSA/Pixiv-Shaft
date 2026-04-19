package ceui.pixiv.ui.novel.reader.model

import android.graphics.RectF

data class Page(
    val index: Int,
    val elements: List<PageElement>,
    val charStart: Int,
    val charEnd: Int,
    val chapterTitle: String? = null,
) {
    val textElements: List<PageElement.Text> get() = elements.filterIsInstance<PageElement.Text>()

    fun containsChar(absoluteCharIndex: Int): Boolean =
        absoluteCharIndex in charStart..charEnd
}

data class PageLocation(
    val pageIndex: Int,
    val charIndex: Int,
)

data class PageGeometry(
    val width: Int,
    val height: Int,
    val paddingLeft: Float,
    val paddingTop: Float,
    val paddingRight: Float,
    val paddingBottom: Float,
) {
    val contentRect: RectF
        get() = RectF(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom,
        )

    val contentWidth: Float get() = width - paddingLeft - paddingRight
    val contentHeight: Float get() = height - paddingTop - paddingBottom
}
