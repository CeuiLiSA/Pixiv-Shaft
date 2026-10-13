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
 * 锚点 [charIndex] 所在的页（issue #864：全文翻译分批上屏，每次重排版都靠它把读者放回原处）。
 *
 * 1. 锚点正好是某页页首（翻页时锚点取的就是页首）→ 那一页。零宽度页（混排插画、整页的
 *    双语译文，charStart == charEnd）只能靠这一条认回来，否则会被推到下一页。
 * 2. 否则取第一个 charEnd **严格大于**锚点的页：锚点落在两页交界（跨页段落续页的页首 ==
 *    前一页的 charEnd）时属于后一页，用 `>=` 会退回前一页。
 * 3. 锚点在全书末尾 → 最后一页；空列表 → 0。
 */
fun List<Page>.indexOfPageForAnchor(charIndex: Int): Int {
    indexOfFirst { it.charStart == charIndex }.takeIf { it >= 0 }?.let { return it }
    return indexOfFirst { it.charEnd > charIndex }.takeIf { it >= 0 } ?: lastIndex.coerceAtLeast(0)
}

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
