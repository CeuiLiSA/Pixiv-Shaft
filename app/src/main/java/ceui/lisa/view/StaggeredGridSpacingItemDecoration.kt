package ceui.lisa.view

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

class StaggeredGridSpacingItemDecoration(
    private val spacing: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect, view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        val layoutParams = view.layoutParams as StaggeredGridLayoutManager.LayoutParams
        val spanIndex = layoutParams.spanIndex

        // Top spacing for all items except the first row
        outRect.top = spacing

        // Horizontal spacing based on column
        if (spanIndex == 0) {
            // Left column (A)
            outRect.left = spacing
            outRect.right = spacing / 2
        } else {
            // Right column (B)
            outRect.left = spacing / 2
            outRect.right = spacing
        }

        // Bottom spacing can also be added if needed
        // outRect.bottom = spacing
    }
}
