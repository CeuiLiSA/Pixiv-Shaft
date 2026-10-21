package ceui.pixiv.feeds

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

/**
 * 把第 [position] 条滚到列表顶部，并让它的顶部离内容区顶边 [offset] 像素。
 *
 * 负的 [offset] 表示让卡片顶部停在内容区上方——也就是保持「这张卡已经滑出去一部分」的样子，
 * 而不是把它整个拉到顶。
 *
 * 两种 LayoutManager 的 `scrollToPositionWithOffset` 不在同一个父类上：瀑布流在
 * [StaggeredGridLayoutManager]，单列 / 网格 / 齐行都在 [LinearLayoutManager]。后者还要换算内边距：
 * 它把 offset 当成相对「列表内边距」的偏移，而调用方给的是相对内容区顶边的像素，两者相差
 * `lm.paddingTop - paddingTop`。
 */
fun RecyclerView.scrollFeedItemToOffset(position: Int, offset: Int) {
    when (val lm = layoutManager) {
        is StaggeredGridLayoutManager -> lm.scrollToPositionWithOffset(position, offset)
        is LinearLayoutManager ->
            lm.scrollToPositionWithOffset(position, offset - (lm.paddingTop - paddingTop))
        else -> scrollToPosition(position)
    }
}