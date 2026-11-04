package ceui.pixiv.ui.novel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.hairlinePx

/**
 * 小说详情的列表装饰（V3「小说详情」：hero 以下是一整张底，区块之间只用左右缩进的细线分隔，不套卡片）。
 *
 * - 宽屏：头卡通栏（模糊背景要铺满，内容列由它自己的 constraintWidth_max 收到 720dp）；其余每一项
 *   左右各让出 (宽 − 760dp) / 2，即 720dp 内容列 + 两侧 20dp 栏距居中。小说卡自带 16dp 内边距，
 *   再补 4dp 对齐 20dp 栏线。
 * - 区块细线：画在每个区块起点（简介 / 标签 / 信息 / 作者 / 相关作品）的上沿，左右缩进到内容列。
 *   紧跟头卡的第一项不画 —— 头卡末尾的统计条 / 系列行已经收了一条线，再画就是双线。
 *   「其他作品」是作者区块里的子行，不算区块起点。
 * - 区块前最后一张小说卡底部补 12dp，卡片别贴着下一条细线。
 */
internal class NovelDetailDecoration(context: Context, @ColorInt lineColor: Int) : RecyclerView.ItemDecoration() {

    private val columnWithGutters = context.dp(760)
    private val gutter = context.dp(20)
    private val cardExtraInset = context.dp(4)
    private val cardGapBeforeSection = context.dp(12)
    private val lineHeight = context.hairlinePx()
    private val paint = Paint().apply { color = lineColor }
    private val lineAlpha = Color.alpha(lineColor)

    private fun sideInset(width: Int): Int = ((width - columnWithGutters) / 2).coerceAtLeast(0)

    private fun itemAt(parent: RecyclerView, position: Int): FeedItem? =
        (parent.adapter as? FeedAdapter)?.currentList?.getOrNull(position)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val item = itemAt(parent, position) ?: return
        if (item is NovelHeaderFeedItem) return
        val side = sideInset(parent.width)
        if (item is NovelFeedItem) {
            outRect.left = side + cardExtraInset
            outRect.right = side + cardExtraInset
            if (isSectionStart(itemAt(parent, position + 1))) outRect.bottom = cardGapBeforeSection
        } else {
            outRect.left = side
            outRect.right = side
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val side = sideInset(parent.width)
        val left = (side + gutter).toFloat()
        val right = (parent.width - side - gutter).toFloat()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position <= 1) continue
            if (!isSectionStart(itemAt(parent, position))) continue
            val top = child.top + child.translationY
            paint.alpha = (lineAlpha * child.alpha).toInt()
            c.drawRect(left, top, right, top + lineHeight, paint)
        }
    }

    private fun isSectionStart(item: FeedItem?): Boolean = when (item) {
        is NovelCaptionFeedItem,
        is NovelTagsFeedItem,
        is NovelInfoFeedItem,
        is NovelAuthorFeedItem -> true
        is NovelSectionHeaderItem -> item.section == NovelDetailSection.RELATED
        else -> false
    }
}
