package ceui.pixiv.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatViewInflater
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.activities.Shaft

/**
 * 滑到边缘的触感（#1193）：用户拖动或惯性滑动把列表 / 滚动页带到首端或末端的那一下轻震，
 * 停在边缘上继续拉不重复震，离开边缘再回来才算下一次。代码触发的滚动（回顶按钮、恢复位置、
 * 设置搜索跳转到某一行）不震。
 *
 * 判定用滚动回调而不是 EdgeEffect：feed 框架的 fragment_feed 等二十多处列表是
 * `overScrollMode="never"`，那种列表根本不会调用 EdgeEffect。
 */
private class ScrollEdgeTracker {

    /** 当前停靠的边缘：-1 首端、1 末端、0 不在边缘。已停靠的边缘不再重复震。 */
    private var restingEdge = 0

    /**
     * 每次实际发生滚动后调用。[direction] 是这次滚动的方向，[canGoOn] 是滚完之后还能不能
     * 继续往这个方向滚。返回 true 表示这次滚动刚好撞上边缘。
     */
    fun onScrolled(direction: Int, canGoOn: Boolean): Boolean {
        if (canGoOn) {
            restingEdge = 0
            return false
        }
        if (restingEdge == direction) return false
        restingEdge = direction
        return true
    }
}

private fun View.playScrollEdgeHaptic() {
    if (Shaft.sSettings?.isScrollEdgeHapticEnable == false) return
    // 最轻的 CLOCK_TICK：撞边是高频的被动反馈，不该比点按重。系统的 SCROLL_LIMIT
    // 是 @hide，公开的 ScrollFeedbackProvider 只服务旋钮类输入、触屏上不出震动。
    // performHapticFeedback 受系统「触感反馈」总开关约束。
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

/** RecyclerView 版：只有经过 DRAGGING 的那一轮滚动（含松手后的惯性）才算用户手势。 */
private class ScrollEdgeHapticListener : RecyclerView.OnScrollListener() {

    private val tracker = ScrollEdgeTracker()
    private var userScrolling = false

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        when (newState) {
            RecyclerView.SCROLL_STATE_DRAGGING -> userScrolling = true
            RecyclerView.SCROLL_STATE_IDLE -> userScrolling = false
        }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        val vertical = recyclerView.layoutManager?.canScrollVertically() ?: return
        val delta = if (vertical) dy else dx
        if (delta == 0) return
        val direction = if (delta > 0) 1 else -1
        val canGoOn = if (vertical) {
            recyclerView.canScrollVertically(direction)
        } else {
            recyclerView.canScrollHorizontally(direction)
        }
        if (tracker.onScrolled(direction, canGoOn) && userScrolling) {
            recyclerView.playScrollEdgeHaptic()
        }
    }
}

/**
 * NestedScrollView 版（发现页、详情页、设置页等整页滚动的容器）。
 *
 * 它没有滚动状态回调，用户手势按「手指按着」或「这次手势甩出的惯性」认定；惯性没撞到边就
 * 停下时标记会留到下一次按下，期间若有代码把它滚到边缘会多震一下，可以接受。
 * 覆写 onScrollChanged 而不是 setOnScrollChangeListener：那是单监听位，BottomBarAutoHide 在用。
 */
private class ScrollEdgeHapticNestedScrollView(
    context: Context,
    attrs: AttributeSet?,
) : NestedScrollView(context, attrs) {

    private val tracker = ScrollEdgeTracker()
    private var touching = false
    private var flinging = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                flinging = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun fling(velocityY: Int) {
        flinging = true
        super.fling(velocityY)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val delta = t - oldt
        if (delta == 0) return
        val direction = if (delta > 0) 1 else -1
        if (tracker.onScrolled(direction, canScrollVertically(direction)) && (touching || flinging)) {
            flinging = false
            playScrollEdgeHaptic()
        }
    }
}

/**
 * 通过 AppTheme 的 `viewInflaterClass` 接管布局里的 RecyclerView / NestedScrollView，让全 app
 * 的滚动页统一带上撞边触感，不用逐页接线。只认这两个确切的 XML 标签，其余返回 null 交还
 * AppCompat 默认流程。代码里 new 出来的（ViewPager2 内部的 RecyclerView、小说滚动阅读器）
 * 不经过这里，翻页和阅读不震是有意的。
 *
 * AppCompat 按类名反射实例化本类，keep 规则见 proguard-rules.pro。
 */
class ShaftViewInflater : AppCompatViewInflater() {

    // 参数照 AppCompat 的 Java 签名声明为可空：它们没有 @NonNull 标注，写成非空类型 Kotlin
    // 会在入口插空检查，任何传 null 的调用方都会在这里崩，而默认流程本身容忍 attrs 为 null。
    override fun createView(context: Context?, name: String?, attrs: AttributeSet?): View? =
        if (context == null) null else when (name) {
            RECYCLER_VIEW_TAG -> RecyclerView(context, attrs).apply {
                addOnScrollListener(ScrollEdgeHapticListener())
            }
            NESTED_SCROLL_VIEW_TAG -> ScrollEdgeHapticNestedScrollView(context, attrs)
            else -> null
        }

    private companion object {
        const val RECYCLER_VIEW_TAG = "androidx.recyclerview.widget.RecyclerView"
        const val NESTED_SCROLL_VIEW_TAG = "androidx.core.widget.NestedScrollView"
    }
}
