package ceui.pixiv.ui.user

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view) // 获取 item 位置
        if (position == RecyclerView.NO_POSITION) return

        val column = position % spanCount // 计算当前 item 是哪一列
        val row = position / spanCount    // 计算当前 item 是哪一行
        val totalItems = parent.adapter?.itemCount ?: 0
        val totalRows = (totalItems + spanCount - 1) / spanCount // 计算总行数（向上取整）

        val halfSpacing = spacing / 2 // 让横向间距变小，匹配视觉效果

        // 处理列间距
        when (column) {
            0 -> { // 第一列
                outRect.left = 0
                outRect.right = halfSpacing
            }
            spanCount - 1 -> { // 最后一列
                outRect.left = halfSpacing
                outRect.right = 0
            }
            else -> { // 中间列
                outRect.left = halfSpacing / 2
                outRect.right = halfSpacing / 2
            }
        }

        // 处理行间距
        outRect.top = if (row == 0) 0 else halfSpacing // 第一行的上方没有间距
        outRect.bottom = if (row < totalRows - 1) halfSpacing else 0 // 只有非最后一行才有底部间距
    }
}
