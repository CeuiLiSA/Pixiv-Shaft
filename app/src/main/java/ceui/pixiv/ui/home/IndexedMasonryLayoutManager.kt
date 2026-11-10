package ceui.pixiv.ui.home

import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedIndexedLayoutManager
import ceui.pixiv.ui.common.justifiedColumnsFor
import kotlin.math.max

/**
 * 首页推荐的正向瀑布流。列分配由 [MasonryPlacementIndex] 确定，任意位置跳转只改变视口坐标，
 * 因此回到榜单头时不会让新 SGLM 从中途反向重新分列。
 *
 * 每次滚动只测量新进入视口的 child；索引构建只遍历数据和高度数字。
 */
internal class IndexedMasonryLayoutManager(
    private val baseColumns: Int,
    private val spacePx: Int,
    initialHeaderWidth: Int = -1,
    initialHeaderHeight: Int = -1,
    private val heightOf: (FeedItem, Int) -> Int,
) : RecyclerView.LayoutManager(), FeedIndexedLayoutManager, RecyclerView.SmoothScroller.ScrollVectorProvider {
    var columnCount: Int = baseColumns
        private set

    private var cachedHeaderWidth = initialHeaderWidth
    private var cachedHeaderHeight = initialHeaderHeight
    override val feedColumnCount: Int get() = columnCount

    private var index: MasonryPlacementIndex? = null
    private var modelWidth = -1
    private var dirty = true
    private var scrollY = 0L
    private var pendingPosition = RecyclerView.NO_POSITION
    private var pendingOffset = 0

    private val measuredOverrides = HashMap<Int, Int>()
    private val itemDecorInsets = Rect()
    private var relayoutPosted = false

    override fun generateDefaultLayoutParams() =
        RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )

    override fun canScrollVertically() = true
    override fun supportsPredictiveItemAnimations() = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.isPreLayout) return
        val anchorView =
            if (pendingPosition == RecyclerView.NO_POSITION) firstVisibleChild() else null
        val anchorPosition = anchorView?.let(::getPosition) ?: RecyclerView.NO_POSITION
        val anchorOffset = anchorView?.top?.minus(paddingTop) ?: 0
        val previousIndex = index
        detachAndScrapAttachedViews(recycler)
        if (state.itemCount == 0 || width <= 0 || height <= 0) {
            index = null
            scrollY = 0
            return
        }
        ensureIndex(recycler, state.itemCount)
        val model = index ?: return
        when {
            pendingPosition in 0 until model.size -> {
                scrollY = model.top[pendingPosition] - pendingOffset
                pendingPosition = RecyclerView.NO_POSITION
            }

            previousIndex !== model && anchorPosition in 0 until model.size -> {
                scrollY = model.top[anchorPosition] - anchorOffset
            }
        }
        scrollY = scrollY.coerceIn(0L, maxScroll(model))
        fill(recycler, model)
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): Int {
        val model = index ?: return 0
        if (childCount == 0 || dy == 0) return 0
        val target = (scrollY + dy).coerceIn(0L, maxScroll(model))
        val consumed = (target - scrollY).toInt()
        if (consumed == 0) return 0
        scrollY = target
        offsetChildrenVertical(-consumed)
        recycleOutside(recycler)
        fill(recycler, model)
        return consumed
    }

    override fun scrollToPosition(position: Int) {
        scrollToFeedPositionWithOffset(position, 0)
    }

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
        startSmoothScroll(LinearSmoothScroller(recyclerView.context).apply { targetPosition = position })
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF {
        val model = index
        val targetTop = if (model != null && targetPosition in 0 until model.size) {
            model.top[targetPosition]
        } else {
            targetPosition.toLong()
        }
        return PointF(0f, if (targetTop < scrollY) -1f else 1f)
    }

    override fun scrollToFeedPositionWithOffset(position: Int, offset: Int) {
        pendingPosition = position
        pendingOffset = offset
        requestLayout()
    }

    override fun findLastVisibleFeedPosition(): Int {
        var last = RecyclerView.NO_POSITION
        for (child in 0 until childCount) {
            val view = getChildAt(child) ?: continue
            if (getDecoratedTop(view) < height - paddingBottom &&
                getDecoratedBottom(view) > paddingTop
            ) {
                last = maxOf(last, getPosition(view))
            }
        }
        return last
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        val anchor = firstVisibleChild()
        putInt("position", anchor?.let(::getPosition) ?: 0)
        putInt("offset", anchor?.top?.minus(paddingTop) ?: 0)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val saved = state as? Bundle ?: return
        pendingPosition = saved.getInt("position", 0)
        pendingOffset = saved.getInt("offset", 0)
        requestLayout()
    }

    override fun onItemsChanged(recyclerView: RecyclerView) = invalidateFeedIndex()
    override fun onItemsAdded(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) =
        invalidateFeedIndex()

    override fun onItemsRemoved(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) =
        invalidateFeedIndex()

    override fun onItemsUpdated(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) =
        invalidateFeedIndex()

    override fun onItemsMoved(recyclerView: RecyclerView, from: Int, to: Int, itemCount: Int) =
        invalidateFeedIndex()

    override fun onItemsUpdated(
        recyclerView: RecyclerView,
        positionStart: Int,
        itemCount: Int,
        payload: Any?
    ) = invalidateFeedIndex()

    override fun invalidateFeedIndex() {
        dirty = true
        measuredOverrides.clear()
        requestLayout()
    }

    private fun ensureIndex(recycler: RecyclerView.Recycler, count: Int) {
        val contentWidth = width - paddingLeft - paddingRight
        val columns = justifiedColumnsFor(
            requireNotNull(attachedView).resources, contentWidth, baseColumns,
        )
        if (!dirty && modelWidth == contentWidth && columnCount == columns && index?.size == count) return
        if (modelWidth != contentWidth || columnCount != columns) measuredOverrides.clear()
        columnCount = columns
        modelWidth = contentWidth
        val adapter = attachedView?.adapter as? FeedAdapter ?: return
        val items = adapter.currentList
        val fullSpan = BooleanArray(count) { position ->
            position < items.size && adapter.spanSizeAt(position, columns) >= columns
        }
        index = MasonryPlacementIndex.build(count, fullSpan, columns, spacePx) { position, column ->
            measuredOverrides[position] ?: when {
                position >= items.size -> 0
                column < 0 && position == 0 && cachedHeaderWidth == contentWidth && cachedHeaderHeight >= 0 ->
                    cachedHeaderHeight

                column < 0 && position == 0 -> measureFullSpan(
                    recycler,
                    position,
                    contentWidth
                ).also { measured ->
                    cachedHeaderWidth = contentWidth
                    cachedHeaderHeight = measured
                }

                column < 0 -> heightOf(items[position], contentWidth)
                else -> heightOf(items[position], cardWidth(contentWidth, columns, column))
            }
        }
        dirty = false
    }

    private fun measureFullSpan(
        recycler: RecyclerView.Recycler,
        position: Int,
        availableWidth: Int
    ): Int {
        val view = recycler.getViewForPosition(position)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val measured = view.measuredHeight
        recycler.recycleView(view)
        return measured
    }

    private fun fill(recycler: RecyclerView.Recycler, model: MasonryPlacementIndex) {
        val viewportBottom = scrollY + height - paddingTop - paddingBottom
        var position = model.firstCandidate(scrollY)
        while (position < model.size && model.top[position] <= viewportBottom) {
            if (model.bottom[position] > scrollY && findViewByPosition(position) == null) {
                val view = recycler.getViewForPosition(position)
                var insertion = 0
                while (insertion < childCount && getPosition(getChildAt(insertion)!!) < position) insertion++
                addView(view, insertion)
                // 齐行布局的 ViewHolder 可能带着旧 ItemDecoration inset。新布局没有
                // ItemDecoration，主动触发 RecyclerView 重算并清零缓存，再测量、摆放。
                calculateItemDecorationsForChild(view, itemDecorInsets)
                val col = model.column[position]
                val contentWidth = width - paddingLeft - paddingRight
                val left: Int
                val right: Int
                if (col < 0) {
                    left = paddingLeft
                    right = width - paddingRight
                } else {
                    val cellLeft = paddingLeft + contentWidth * col / columnCount
                    val cellRight = paddingLeft + contentWidth * (col + 1) / columnCount
                    left = cellLeft + if (col == 0) spacePx else spacePx / 2
                    right = cellRight - if (col == columnCount - 1) spacePx else spacePx / 2
                }
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        max(1, right - left),
                        View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                if (view.measuredHeight.toLong() != model.bottom[position] - model.top[position]) {
                    measuredOverrides[position] = view.measuredHeight
                    if (position == 0 && col < 0) {
                        cachedHeaderWidth = contentWidth
                        cachedHeaderHeight = view.measuredHeight
                    }
                    dirty = true
                    // onLayoutChildren 内直接 requestLayout 可能被 RecyclerView 的布局拦截吞掉。
                    attachedView?.let { list ->
                        if (!relayoutPosted) {
                            relayoutPosted = true
                            list.post {
                                relayoutPosted = false
                                if (dirty && attachedView === list) requestLayout()
                            }
                        }
                    }
                }
                val top = (model.top[position] - scrollY).toInt() + paddingTop
                layoutDecorated(view, left, top, right, top + view.measuredHeight)
            }
            position++
        }
    }

    private fun recycleOutside(recycler: RecyclerView.Recycler) {
        for (child in childCount - 1 downTo 0) {
            val view = getChildAt(child) ?: continue
            if (getDecoratedBottom(view) < paddingTop || getDecoratedTop(view) > height - paddingBottom) {
                removeAndRecycleView(view, recycler)
            }
        }
    }

    private fun firstVisibleChild(): View? {
        var best: View? = null
        for (child in 0 until childCount) {
            val view = getChildAt(child) ?: continue
            if (getDecoratedBottom(view) <= paddingTop) continue
            if (best == null || getPosition(view) < getPosition(best)) best = view
        }
        return best
    }

    private fun maxScroll(model: MasonryPlacementIndex): Long =
        (model.contentBottom + spacePx - (height - paddingTop - paddingBottom)).coerceAtLeast(0L)

    private fun cardWidth(contentWidth: Int, columns: Int, column: Int): Int {
        val cellLeft = contentWidth * column / columns
        val cellRight = contentWidth * (column + 1) / columns
        val left = if (column == 0) spacePx else spacePx / 2
        val right = if (column == columns - 1) spacePx else spacePx / 2
        return (cellRight - cellLeft - left - right).coerceAtLeast(1)
    }

    private var attachedView: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        attachedView = view
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        attachedView = null
        relayoutPosted = false
    }
}