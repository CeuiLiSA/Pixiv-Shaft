package ceui.pixiv.witstudio.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat

/**
 * 把子 View 的嵌套滚动「截停」在这一层，不再向上抛给 `BottomSheetBehavior`。
 *
 * ## 解决什么
 * 列表 + bottom sheet 的默认组合里，列表滚到顶 / 底之后**多余的拖拽与快速滑动（fling）**
 * 会被 `BottomSheetBehavior` 当成「拖 sheet」的输入 —— 表现为「滑列表很容易把 sheet 拖走、
 * 甚至关掉」。本容器认领竖直方向的嵌套滚动但一律不消费：列表照常自己滚，滚到边界后的
 * 余量就地丢弃，sheet 只由把手 / 标题 / 底部动作条这些**非列表区域**拖拽。
 *
 * ## 为什么不用 RecyclerView.setNestedScrollingEnabled(false)
 * `BottomSheetBehavior` 在 `onLayoutChild` 里只把 `isNestedScrollingEnabled() == true` 的后代
 * 登记成「滚动子视图」，再用这份登记判断触摸是否落在列表上（`touchingScrollingChild`，见其
 * `tryCaptureView`）。一旦把 RecyclerView 的嵌套滚动关掉，它就认不出触摸落在列表上，反而会
 * 把触摸拦走 —— 列表直接滚不动。所以这里**保留** RecyclerView 的嵌套滚动，只在中间截停。
 */
public class WitNestedScrollBlockingLayout @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), NestedScrollingParent3 {

    private val parentHelper = NestedScrollingParentHelper(this)

    /** 认领竖直嵌套滚动（认领才轮不到上层 sheet），但下面所有回调都不消费。 */
    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean =
        axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        parentHelper.onNestedScrollAccepted(child, target, axes, type)
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        parentHelper.onStopNestedScroll(target, type)
    }

    /** 一律不消费：列表自己滚，竖直余量不再驱动 sheet。 */
    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int): Unit = Unit

    /** 同上：滚到边界后多余的拖拽 / fling 余量在这里丢弃。 */
    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray,
    ): Unit = Unit

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int): Unit =
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onStopNestedScroll(target: View): Unit = onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray): Unit =
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
    ): Unit = Unit

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
    ): Unit = onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)

    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean = false

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean = false

    override fun getNestedScrollAxes(): Int = parentHelper.nestedScrollAxes
}