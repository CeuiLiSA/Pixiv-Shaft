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
 * 小说系列页的列表装饰（V3「小说系列」：页头以下是一整张底，区块之间只用细线，不套卡片）。
 *
 * - 宽屏：每一项左右各让出 (宽 − 760dp) / 2，即 720dp 内容列 + 两侧 20dp 栏距居中。页头的 Hero 卡
 *   不像小说详情那样通栏，所以页头也一起收进内容列。
 * - 区块细线：画在每个区块（简介 / 作者 / 信息 / 目录）的上沿，左右缩进到内容列。
 * - 目录是通用小说卡（recy_novel）：同小说详情的相关作品，卡自带 16dp 内边距、再补 4dp 对齐 20dp
 *   栏线；卡与卡之间不画线（无界平铺，#1038）。
 * - 作者区块自身只留 12dp 下边距（它在小说详情里下面紧跟「其他作品」子行），这里补 12dp 凑齐
 *   区块间统一的 24dp。
 */
internal class NovelSeriesDecoration(context: Context, @ColorInt lineColor: Int) : RecyclerView.ItemDecoration() {

    private val columnWithGutters = context.dp(760)
    private val gutter = context.dp(20)
    private val cardExtraInset = context.dp(4)
    private val authorBottomExtra = context.dp(12)
    private val lineHeight = context.hairlinePx()
    private val paint = Paint().apply { color = lineColor }
    private val lineAlpha = Color.alpha(lineColor)

    private fun sideInset(width: Int): Int = ((width - columnWithGutters) / 2).coerceAtLeast(0)

    private fun itemAt(parent: RecyclerView, position: Int): FeedItem? =
        (parent.adapter as? FeedAdapter)?.currentList?.getOrNull(position)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val item = itemAt(parent, position)
        val side = sideInset(parent.width) + if (item is NovelFeedItem) cardExtraInset else 0
        outRect.left = side
        outRect.right = side
        if (item is NovelAuthorFeedItem) outRect.bottom = authorBottomExtra
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val side = sideInset(parent.width)
        val left = (side + gutter).toFloat()
        val right = (parent.width - side - gutter).toFloat()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            if (!isSectionStart(itemAt(parent, position))) continue
            val top = child.top + child.translationY
            paint.alpha = (lineAlpha * child.alpha).toInt()
            c.drawRect(left, top, right, top + lineHeight, paint)
        }
    }

    private fun isSectionStart(item: FeedItem?): Boolean = when (item) {
        is NovelSeriesCaptionFeedItem,
        is NovelAuthorFeedItem,
        is NovelSeriesInfoFeedItem,
        is NovelSeriesTocHeaderFeedItem -> true
        else -> false
    }
}
