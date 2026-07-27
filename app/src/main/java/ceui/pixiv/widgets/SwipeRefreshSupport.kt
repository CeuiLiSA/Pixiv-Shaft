package ceui.pixiv.widgets

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ceui.lisa.utils.V3Palette

/**
 * 下拉刷新转圈的统一取色。SwipeRefreshLayout 默认是 Material 蓝箭头 + #FAFAFA 近白底盘，
 * 既不跟主题也不跟日夜，而这两个颜色 XML 设不了、只能在代码里设——所以每个用到它的页面
 * 都得调一次本方法。取色口径与 [ceui.pixiv.feeds.FeedFragment] 完全一致：
 * 箭头 textAccent、底盘 cardFill，日夜两支恒为「浅箭头深底 / 深箭头浅底」，对比度不会塌。
 */
fun SwipeRefreshLayout.applyV3RefreshTheme() {
    val palette = V3Palette.from(context)
    setColorSchemeColors(palette.textAccent)
    setProgressBackgroundColorSchemeColor(palette.cardFill)
}

/**
 * 把「内容还能不能往上滚」的判定接到真正在滚的那个 View 上。
 *
 * SwipeRefreshLayout 只问它的**直接**子 View（`canChildScrollUp()` 里的 mTarget）。隔着一层
 * 容器时（如 fragment_base_list 的 `listContainer` 里并排放着列表和空态）那层永远返回 false
 * ——FrameLayout 自己不滚——于是列表滚到中段往下拖也会被判成「已经在顶部」而触发刷新。
 *
 * 只统计 VISIBLE 的候选：空态显示时列表是 INVISIBLE 的，那时该由空态的滚动容器说了算。
 */
fun SwipeRefreshLayout.scrollUpFrom(vararg candidates: View) {
    setOnChildScrollUpCallback { _, _ ->
        candidates.any { it.visibility == View.VISIBLE && it.canScrollVertically(-1) }
    }
}

/**
 * 滚到接近底部时触发翻页。
 *
 * SwipeRefreshLayout 只做下拉刷新，没有 SmartRefreshLayout 那种上拉 footer——从 SmartRefresh
 * 迁过来之后，所有「加载下一页」统一由本监听器驱动（与 feeds 框架 `onNearEnd` 的语义一致：
 * 滚动接近末尾就预取，用户感知不到翻页动作）。
 *
 * ⚠️ [onLoadMore] 会在一次滚动里被反复调用（每帧都可能命中），**重入保护归调用方**：
 * ViewModel 侧用「正在加载中就直接 return」的守卫，或者外面挂 [isEnabled] 开关。
 *
 * @param prefetchDistance 距列表末尾还有几条时开始预取。
 */
class LoadMoreScrollListener @JvmOverloads constructor(
    private val onLoadMore: Runnable,
    private val prefetchDistance: Int = 4,
) : RecyclerView.OnScrollListener() {

    /** 临时关掉翻页（如历史页进入搜索态，可见列表已是 LIKE 结果，翻页无意义）。 */
    @JvmField
    var isEnabled: Boolean = true

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        // 只在往下滚时判定：回滚经过「末尾附近」不该再触发一次翻页
        if (!isEnabled || dy <= 0) return
        val manager = recyclerView.layoutManager ?: return
        val total = manager.itemCount
        if (total == 0) return
        if (lastVisiblePosition(manager) >= total - 1 - prefetchDistance) {
            onLoadMore.run()
        }
    }

    private fun lastVisiblePosition(manager: RecyclerView.LayoutManager): Int = when (manager) {
        // GridLayoutManager 继承自 LinearLayoutManager，走同一支
        is LinearLayoutManager -> manager.findLastVisibleItemPosition()
        is StaggeredGridLayoutManager -> {
            // 瀑布流每列各有各的末尾，取所有列里最靠后的那条；
            // 传 null 让它自己开数组，列数变化（用户改「每行几列」）时不会越界。
            manager.findLastVisibleItemPositions(null).maxOrNull() ?: RecyclerView.NO_POSITION
        }

        else -> RecyclerView.NO_POSITION
    }
}
