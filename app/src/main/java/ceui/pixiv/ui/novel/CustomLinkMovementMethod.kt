package ceui.pixiv.ui.novel

import android.text.method.LinkMovementMethod
import android.view.MotionEvent
import android.widget.TextView
import timber.log.Timber

class CustomLinkMovementMethod(private val onLinkClick: (String) -> Unit) : LinkMovementMethod() {

    private var isSliding = false  // 标记是否发生了滑动
    private var startX = 0f  // 记录按下时的 X 坐标
    private var startY = 0f  // 记录按下时的 Y 坐标

    override fun onTouchEvent(widget: TextView, buffer: android.text.Spannable, event: MotionEvent): Boolean {
        // 记录触摸事件的坐标
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录按下时的位置
                startX = x
                startY = y
                isSliding = false  // 初始状态没有滑动
            }
            MotionEvent.ACTION_MOVE -> {
                // 判断是否发生了滑动
                val deltaX = Math.abs(x - startX)
                val deltaY = Math.abs(y - startY)
                if (deltaX > 20 || deltaY > 20) {  // 如果移动超过一定距离，就认为是滑动
                    isSliding = true
                }
            }
            MotionEvent.ACTION_UP -> {
                // 手指抬起时，如果没有滑动，处理点击事件
                if (!isSliding) {
                    val layout = widget.layout
                    val line = layout.getLineForVertical(y.toInt())
                    val offset = layout.getOffsetForHorizontal(line, x)
                    val link = getClickedLink(buffer, offset)

                    // 如果有链接，触发回调
                    if (link != null) {
                        onLinkClick(link)  // 调用回调
                        Timber.d("Link clicked: $link")
                    }
                }
            }
        }

        // 返回 true 表示事件已处理，系统不会继续处理
        return true
    }

    // 获取点击位置的链接
    private fun getClickedLink(buffer: android.text.Spannable, offset: Int): String? {
        val spans = buffer.getSpans(offset, offset, android.text.style.URLSpan::class.java)
        if (spans.isNotEmpty()) {
            return spans[0].url
        }
        return null
    }
}
