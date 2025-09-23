package ceui.pixiv.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

class CustomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var startY = 0f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = e.y
                // 必须调用父类，初始化内部滚动状态
                super.onInterceptTouchEvent(e)
                // 不拦截，先让自己接收
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - startY
                if (dy < 0 && !canScrollVertically(1)) {
                    // 向上滑动，但 RecyclerView 已经到底了 -> 不处理，让父布局拦截
                    parent.requestDisallowInterceptTouchEvent(false)
                    return false
                } else if (dy > 0 && !canScrollVertically(-1)) {
                    // 向下滑动，但 RecyclerView 已经到顶部 -> 不处理，让父布局拦截
                    parent.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                // 自己可以滑动 -> 不让父布局拦截
                parent.requestDisallowInterceptTouchEvent(true)
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 确保 RecyclerView 正常处理自己的滚动
        return super.onTouchEvent(e)
    }
}
