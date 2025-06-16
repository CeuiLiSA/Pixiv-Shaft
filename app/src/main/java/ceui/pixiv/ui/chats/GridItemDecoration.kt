package ceui.pixiv.ui.chats

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GridItemDecoration(
    private val spanCount: Int,
    private val spacing: Int,
    private val includeEdge: Boolean
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val layoutManager = parent.layoutManager as GridLayoutManager
        val spanSize = layoutManager.spanSizeLookup.getSpanSize(position)
        val spanIndex = layoutManager.spanSizeLookup.getSpanIndex(position, spanCount)

        // Determine the column this item is in
        val column = spanIndex % spanCount

        if (includeEdge) {
            if (spanSize == spanCount) {
                // Item takes up the whole row
                outRect.left = spacing
                outRect.right = spacing
            } else {
                outRect.left = spacing - column * spacing / spanCount
                outRect.right = (column + spanSize) * spacing / spanCount
            }
            outRect.bottom = spacing

            // Only add top spacing to the first row
            if (isFirstRow(position, spanSize, layoutManager)) {
                outRect.top = spacing
            }
        } else {
            if (spanSize == spanCount) {
                // Item takes up the whole row
                outRect.left = 0
                outRect.right = 0
            } else {
                outRect.left = column * spacing / spanCount
                outRect.right = spacing - (column + spanSize) * spacing / spanCount
            }

            if (!isFirstRow(position, spanSize, layoutManager)) {
                outRect.top = spacing
            }
        }
    }

    private fun isFirstRow(position: Int, spanSize: Int, layoutManager: GridLayoutManager): Boolean {
        if (position == 0) return true

        val previousPosition = position - 1
        val previousSpanSize = layoutManager.spanSizeLookup.getSpanSize(previousPosition)
        return position < spanCount || previousSpanSize == spanCount
    }
}