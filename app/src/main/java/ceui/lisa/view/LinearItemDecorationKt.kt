package ceui.lisa.view

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class LinearItemDecorationKt(private val space: Int, private val offset: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view) // 获取子项的适配器位置

        // 清空所有偏移量
        outRect.set(0, 0, 0, 0)

        // 如果位置大于或等于 offset，才应用边距
        if (position >= offset) {
            outRect.left = space
            outRect.right = space
            outRect.bottom = space

            // 只有在 offset 位置后的第一个 item 上添加顶部边距
            if (position == offset) {
                outRect.top = space
            }
        }
    }
}
