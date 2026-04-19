package ceui.pixiv.ui.novel.reader.feature

import android.graphics.PointF
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageElement

/**
 * Turns screen-space touch points on a PageView into absolute source character
 * offsets (and back). Used by the Fragment to convert long-press coordinates
 * into selection ranges, and by the selection toolbar to anchor itself above
 * the selection's screen location.
 */
object TextHitTester {

    /**
     * Absolute source offset of the character under [x]/[y] within [page], or
     * null if the touch doesn't hit any text element.
     */
    fun hit(page: Page, paddingLeft: Float, x: Float, y: Float): Int? {
        for (el in page.elements) {
            if (el !is PageElement.Text) continue
            if (y < el.top || y > el.bottom) continue
            val layout = el.layout
            val localTop = el.top
            val startLineTop = layout.getLineTop(el.startLine).toFloat()
            val localY = y - localTop + startLineTop
            val line = layout.getLineForVertical(localY.toInt())
            if (line < el.startLine || line >= el.endLineExclusive) continue
            val localX = (x - paddingLeft).coerceAtLeast(0f)
            val offset = layout.getOffsetForHorizontal(line, localX)
            val paragraphStart = layout.getLineStart(el.startLine)
            return el.absoluteCharStart - paragraphStart + offset
        }
        return null
    }

    /**
     * Reverse lookup: where does [absoluteOffset] render on [page]? Returns
     * (x, y) at the top-left of the glyph, or null when the offset is not
     * visible on this page.
     */
    fun screenPosition(page: Page, paddingLeft: Float, absoluteOffset: Int): PointF? {
        for (el in page.elements) {
            if (el !is PageElement.Text) continue
            if (absoluteOffset < el.absoluteCharStart || absoluteOffset > el.absoluteCharEnd) continue
            val layout = el.layout
            val paragraphStart = layout.getLineStart(el.startLine)
            val local = absoluteOffset - el.absoluteCharStart + paragraphStart
            val line = layout.getLineForOffset(local).coerceIn(el.startLine, el.endLineExclusive - 1)
            val x = paddingLeft + layout.getPrimaryHorizontal(local)
            val startLineTop = layout.getLineTop(el.startLine).toFloat()
            val y = el.top + (layout.getLineBottom(line).toFloat() - startLineTop)
            return PointF(x, y)
        }
        return null
    }

    /**
     * Initial selection heuristic: a single CJK character, or the enclosing
     * "word" for Latin scripts. [absoluteOffset] is the long-press position in
     * source coordinates.
     */
    fun initialSelectionAt(sourceText: String, absoluteOffset: Int): IntRange {
        val len = sourceText.length
        if (len == 0) return 0..0
        val idx = absoluteOffset.coerceIn(0, len - 1)
        val c = sourceText[idx]
        return if (isCjk(c)) {
            idx..(idx + 1).coerceAtMost(len)
        } else {
            var start = idx
            while (start > 0 && isLatinWordChar(sourceText[start - 1])) start--
            var end = idx
            while (end < len && isLatinWordChar(sourceText[end])) end++
            if (end == start) idx..(idx + 1).coerceAtMost(len) else start until end
        }
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3040..0x309F ||
            code in 0x30A0..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    private fun isLatinWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '\''
}
