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
        val position = parent.getChildAdapterPosition(view) // item 位置
        if (position == RecyclerView.NO_POSITION) return

        val column = position % spanCount // 当前 item 的列索引
        val row = position / spanCount    // 当前 item 的行索引
        val totalItems = parent.adapter?.itemCount ?: 0
        val totalRows = (totalItems + spanCount - 1) / spanCount // 计算总行数（向上取整）

        // 处理列间距
        when (column) {
            0 -> { // 第一列
                outRect.left = 0
                outRect.right = spacing
            }
            spanCount - 1 -> { // 最后一列
                outRect.left = spacing
                outRect.right = 0
            }
            else -> { // 中间列
                outRect.left = spacing / 2
                outRect.right = spacing / 2
            }
        }

        // 处理行间距
        outRect.top = if (row == 0) 0 else spacing // 第一行的上方没有间距
        outRect.bottom = if (row < totalRows - 1) spacing else 0 // 只有非最后一行才有底部间距
    }
}
