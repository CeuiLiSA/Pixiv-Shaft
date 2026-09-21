package ceui.pixiv.ui.prime

import android.content.Context
import android.util.AttributeSet
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.SkeletonBlock

/**
 * 标签目录首屏骨架：标题、数量和三张方形预览各自占位。
 *
 * 三个标签列表（内置标签、作品库标签、置顶标签）都使用同一种内容节奏；[style] 只调整
 * 置顶标签 V3 卡片额外的内边距与间距，保证骨架换成真卡时不跳位。
 */
class TagShelfSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    val style: Style = Style.CATALOG,
) : FeedSkeletonView(context, attrs) {

    enum class Style(
        val outerGapDp: Float,
        val horizontalPaddingDp: Float,
        val verticalPaddingDp: Float,
        val titleLines: Int,
        val secondaryTopGapDp: Float,
        val previewTopGapDp: Float,
    ) {
        CATALOG(18f, 0f, 0f, 1, 4f, 8f),
        PINNED(12f, 20f, 16f, 2, 4f, 10f),
    }

    private val titleHeight = 20f
    private val titleLineGap = 6f
    private val countHeight = 14f
    private val previewGap = 4f
    private val lineCorner = 4f

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val gap = style.outerGapDp * density
        val horizontalPadding = style.horizontalPaddingDp * density
        val verticalPadding = style.verticalPaddingDp * density
        val titleHeightPx = titleHeight * density
        val titleLineGapPx = titleLineGap * density
        val countHeightPx = countHeight * density
        val secondaryTopGapPx = style.secondaryTopGapDp * density
        val previewTopGapPx = style.previewTopGapDp * density
        val previewGapPx = previewGap * density
        val lineCornerPx = lineCorner * density
        val cardWidth = w - gap * 2f
        val previewWidth = cardWidth - horizontalPadding * 2f
        val slotWidth = (previewWidth - previewGapPx * 2f) / 3f
        if (cardWidth <= 0f || slotWidth <= 0f) return

        val titleWidth = (cardWidth - horizontalPadding * 2f) * 0.42f
        val countWidth = (cardWidth - horizontalPadding * 2f) * 0.28f
        val titleAreaHeight = titleHeightPx * style.titleLines +
            titleLineGapPx * (style.titleLines - 1)
        val contentHeight = verticalPadding * 2f + titleAreaHeight +
            secondaryTopGapPx + countHeightPx + previewTopGapPx + slotWidth

        var top = gap
        while (top < h) {
            val contentLeft = gap + horizontalPadding
            val titleTop = top + verticalPadding
            out.add(block(contentLeft, titleTop, titleWidth, titleHeightPx, lineCornerPx))

            if (style.titleLines == 1) {
                val countTop = titleTop + titleAreaHeight + secondaryTopGapPx
                out.add(block(contentLeft, countTop, countWidth, countHeightPx, lineCornerPx))
            } else {
                val subtitleTop = titleTop + titleAreaHeight - countHeightPx
                out.add(block(contentLeft, subtitleTop, countWidth, countHeightPx, lineCornerPx))
            }

            val previewTop = titleTop + titleAreaHeight + secondaryTopGapPx +
                countHeightPx + previewTopGapPx
            for (index in 0 until 3) {
                val left = contentLeft + index * (slotWidth + previewGapPx)
                out.add(block(left, previewTop, slotWidth, slotWidth, 8f * density))
            }

            top += contentHeight + gap
        }
    }
}
