package ceui.pixiv.widgets

import android.view.MotionEvent
import android.view.View
import androidx.viewpager2.widget.ViewPager2

fun setupVerticalAwareViewPager2(viewPager2: ViewPager2) {
    viewPager2.setOnTouchListener(object : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    // 默认先不要让父控件拦截
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.x - startX)
                    val dy = Math.abs(event.y - startY)

                    if (dy > dx) {
                        // 纵向滑动 → 不拦截
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    } else {
                        // 横向滑动 → 允许 ViewPager2 翻页
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return false // 不消费事件，让 RecyclerView 自己处理
        }
    })
}
