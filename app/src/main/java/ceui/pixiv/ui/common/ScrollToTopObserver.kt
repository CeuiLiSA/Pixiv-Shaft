package ceui.pixiv.ui.common

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

class ScrollToTopObserver(
    private val recyclerView: RecyclerView,
    private val adapter: RecyclerView.Adapter<*>
) : RecyclerView.AdapterDataObserver() {

    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
        if (recyclerView.isAttachedToWindow && positionStart == 0) {
            scrollToTop()
            adapter.unregisterAdapterDataObserver(this)
        }
    }

    override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payloads: Any?) {
        if (recyclerView.isAttachedToWindow && positionStart == 0) {
            scrollToTop()
            adapter.unregisterAdapterDataObserver(this)
        }
    }

    fun scrollToTop() {
        val layoutManager = recyclerView.layoutManager
        if (layoutManager is StaggeredGridLayoutManager) {
            layoutManager.invalidateSpanAssignments()
            layoutManager.scrollToPositionWithOffset(0, 0)
        } else {
            recyclerView.scrollToPosition(0)
        }
    }
}
