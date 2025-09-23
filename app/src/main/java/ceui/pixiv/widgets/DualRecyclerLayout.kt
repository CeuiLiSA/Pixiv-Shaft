package ceui.pixiv.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.NestedScrollingParent2
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R

class DualRecyclerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs), NestedScrollingParent2 {

    private lateinit var galleryList: RecyclerView
    private lateinit var artworkList: RecyclerView

    override fun onFinishInflate() {
        super.onFinishInflate()
        galleryList = findViewById(R.id.gallery_list)
        artworkList = findViewById(R.id.artwork_list_view)
    }

    // ---- NestedScrollingParent2 必须实现的接口 ----
    override fun onStartNestedScroll(
        child: View,
        target: View,
        axes: Int,
        type: Int
    ): Boolean {
        return axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {}

    override fun onStopNestedScroll(target: View, type: Int) {}
    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int
    ) {
    }

    override fun onNestedPreScroll(
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int
    ) {
        if (target === galleryList) {
            val galleryCanScroll = canScrollVertically(galleryList, dy)
            val artworkCanScroll = canScrollVertically(artworkList, dy)

            if (galleryCanScroll) {
                // gallery 自己消耗滚动
                galleryList.scrollBy(0, dy)
                consumed[1] = dy
            } else {
                // gallery 到底了，底部接管，同时顶部也偏移
                if (artworkCanScroll) {
                    artworkList.scrollBy(0, dy)
                }

                // 顶部 gallery 偏移，实现折叠效果
                val newTranslationY =
                    (galleryList.translationY - dy).coerceIn(-galleryList.height.toFloat(), 0f)
                galleryList.translationY = newTranslationY
                consumed[1] = dy
            }
        } else if (target === artworkList) {
            // artwork 滑动
            if (canScrollVertically(artworkList, dy)) {
                artworkList.scrollBy(0, dy)
                consumed[1] = dy
            }
        }
    }


    // ---- 必须要有 onLayout/onMeasure 才能正确布局 ----
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        measureChildWithMargins(galleryList, widthMeasureSpec, 0, heightMeasureSpec, 0)
        measureChildWithMargins(artworkList, widthMeasureSpec, 0, heightMeasureSpec, 0)

        setMeasuredDimension(width, height)
    }

    override fun onLayout(p0: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        val galleryHeight = (height * 0.7f).toInt()
        val artworkHeight = height - galleryHeight

        galleryList.layout(0, 0, width, galleryHeight)
        artworkList.layout(0, galleryHeight, width, galleryHeight + artworkHeight)
    }

    private fun canScrollVertically(rv: RecyclerView, dy: Int): Boolean {
        return rv.canScrollVertically(if (dy > 0) 1 else -1)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateLayoutParams(p: LayoutParams): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun checkLayoutParams(p: LayoutParams): Boolean {
        return p is MarginLayoutParams
    }
}
