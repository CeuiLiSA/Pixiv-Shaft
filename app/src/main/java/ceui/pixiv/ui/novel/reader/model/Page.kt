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

/**
 * 锚点 [charIndex] 所在的页：第一个 charEnd **严格大于**锚点的页。锚点恰好落在两页交界
 * （跨页段落续页的页首 == 前一页的 charEnd）时属于后一页——用 `>=` 会退回前一页，
 * 全文翻译分批上屏时每次重排版都把正在读续页的人拽回去（issue #864）。
 * 锚点在全书末尾（没有更大的 charEnd）→ 最后一页；空列表 → 0。
 */
fun List<Page>.indexOfPageForAnchor(charIndex: Int): Int =
    indexOfFirst { it.charEnd > charIndex }.takeIf { it >= 0 } ?: lastIndex.coerceAtLeast(0)

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
