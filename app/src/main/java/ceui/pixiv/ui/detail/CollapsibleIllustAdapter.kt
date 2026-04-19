package ceui.pixiv.ui.detail

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.lisa.models.IllustsBean

/**
 * V3-only IllustAdapter that hides all but the first [collapsedCount] pages of a
 * multi-page illust. Call [expand] to reveal the rest.
 *
 * Older pages keep using the vanilla IllustAdapter unchanged — this subclass just
 * clamps getItemCount() and sets isFullSpan for the V3 StaggeredGridLayoutManager.
 */
class CollapsibleIllustAdapter(
    activity: FragmentActivity,
    fragment: Fragment,
    private val illust: IllustsBean,
    maxHeight: Int,
    isForceOriginal: Boolean,
    private val collapsedCount: Int = DEFAULT_COLLAPSED
) : IllustAdapter(activity, fragment, illust, maxHeight, isForceOriginal) {

    private var expanded = false

    val totalPages: Int get() = illust.page_count
    val hiddenCount: Int get() = (totalPages - collapsedCount).coerceAtLeast(0)
    val isCollapsed: Boolean get() = !expanded && totalPages > collapsedCount

    override fun getItemCount(): Int {
        val total = super.getItemCount()
        return if (expanded) total else minOf(total, collapsedCount)
    }

    fun expand() {
        if (expanded) return
        val prev = itemCount
        expanded = true
        val added = itemCount - prev
        if (added > 0) notifyItemRangeInserted(prev, added)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder<RecyIllustDetailBinding>) {
        super.onViewAttachedToWindow(holder)
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams) {
            lp.isFullSpan = true
        }
    }

    companion object {
        /** How many pages to show before collapsing. */
        const val DEFAULT_COLLAPSED = 1

        /** 1P and 2P are always shown in full; 3P and up get collapsed. */
        fun shouldCollapse(pageCount: Int, collapsedCount: Int = DEFAULT_COLLAPSED): Boolean {
            return pageCount > 2
        }
    }
}
