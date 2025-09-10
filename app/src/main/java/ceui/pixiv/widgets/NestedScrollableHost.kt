package ceui.pixiv.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.ORIENTATION_HORIZONTAL
import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * Based on https://github.com/android/views-widgets-samples/blob/master/ViewPager2/app/src/main/java/androidx/viewpager2/integration/testapp/NestedScrollableHost.kt
 * but modified by Seth Westphal (westy92@gmail.com) to support nested ViewPager2.
 */

/**
 * Layout to wrap a scrollable component inside a ViewPager2. Provided as a solution to the problem
 * where pages of ViewPager2 have nested scrollable elements that scroll in the same direction as
 * ViewPager2. The scrollable element needs to be the immediate and only child of this host layout.
 *
 * This solution has limitations when using multiple levels of nested scrollable elements
 * (e.g. a horizontal RecyclerView in a vertical RecyclerView in a horizontal ViewPager2).
 */
class NestedScrollableHost : FrameLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var touchSlop = 0
    private var initialX = 0f
    private var initialY = 0f
    private val ancestorViewPagers: List<ViewPager2>
        get() {
            val ancestors = mutableListOf<ViewPager2>()
            var v: View? = parent as? View
            while (v != null) {
                if (v is ViewPager2) ancestors.add(v)
                v = v.parent as? View
            }
            return ancestors
        }

    private val child: View? get() = if (childCount > 0) getChildAt(0) else null

    init {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    private fun canChildScroll(orientation: Int, delta: Float): Boolean {
        val direction = -delta.sign.toInt()
        return when (orientation) {
            0 -> child?.canScrollHorizontally(direction) == true
            1 -> child?.canScrollVertically(direction) == true
            else -> throw IllegalArgumentException("Unknown scroll orientation")
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        handleInterceptTouchEvent(e)
        return super.onInterceptTouchEvent(e)
    }

    private fun handleInterceptTouchEvent(e: MotionEvent) {
        for (ancestor in ancestorViewPagers) {
            val orientation = ancestor.orientation

            // Early return if child can't scroll in same direction as parent
            if (!canChildScroll(orientation, -1f) && !canChildScroll(orientation, 1f)) {
                continue
            }

            if (e.action == MotionEvent.ACTION_DOWN) {
                initialX = e.x
                initialY = e.y
                parent.requestDisallowInterceptTouchEvent(true)
            } else if (e.action == MotionEvent.ACTION_MOVE) {
                handleActionMove(e, orientation)
            }
        }
    }

    private fun handleActionMove(e: MotionEvent, orientation: Int) {
        val dx = e.x - initialX
        val dy = e.y - initialY
        val isVpHorizontal = orientation == ORIENTATION_HORIZONTAL

        // assuming ViewPager2 touch-slop is 2x touch-slop of child
        val scaledDx = dx.absoluteValue * if (isVpHorizontal) TOUCH_SLOP_HALF else TOUCH_SLOP
        val scaledDy = dy.absoluteValue * if (isVpHorizontal) TOUCH_SLOP else TOUCH_SLOP_HALF

        if (scaledDx > touchSlop || scaledDy > touchSlop) {
            if (isVpHorizontal == (scaledDy > scaledDx)) {
                // Gesture is perpendicular, allow all parents to intercept
                parent.requestDisallowInterceptTouchEvent(false)
            } else {
                // Gesture is parallel, query child if movement in that direction is possible
                if (canChildScroll(orientation, if (isVpHorizontal) dx else dy)) {
                    // Child can scroll, disallow all parents to intercept
                    parent.requestDisallowInterceptTouchEvent(true)
                } else {
                    // Child cannot scroll, allow all parents to intercept
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
    }

    companion object {
        private const val TOUCH_SLOP = 1f
        private const val TOUCH_SLOP_HALF = .5f
    }
}
