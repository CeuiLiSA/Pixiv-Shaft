package ceui.pixiv.ui.detail

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.lisa.models.IllustsBean

/**
 * V3-only IllustAdapter that hides all but the first [collapsedCount] pages of a
 * multi-page illust. Call [expand] to reveal the rest.
 *
 * The "展开剩余 X 张" CTA renders as a bottom scrim + glass pill overlaid on the
 * FIRST page's image itself — no separate adapter row needed.
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
        // Refresh pos 0 so the CTA overlay is hidden on the next bind (if the
        // click-driven fade-out was interrupted by a rebind).
        notifyItemChanged(0, PAYLOAD_OVERLAY_ONLY)
    }

    override fun onBindViewHolder(holder: ViewHolder<RecyIllustDetailBinding>, position: Int) {
        super.onBindViewHolder(holder, position)
        bindExpandOverlay(holder, position)
    }

    override fun onBindViewHolder(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PAYLOAD_OVERLAY_ONLY)) {
            bindExpandOverlay(holder, position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindExpandOverlay(holder: ViewHolder<RecyIllustDetailBinding>, position: Int) {
        val (overlay, pill, label) = overlayOf(holder) ?: return

        if (position != 0 || !isCollapsed) {
            overlay.animate().cancel()
            overlay.visibility = View.GONE
            overlay.alpha = 1f
            pill.animate().cancel()
            pill.scaleX = 1f
            pill.scaleY = 1f
            pill.setOnClickListener(null)
            pill.setOnTouchListener(null)
            return
        }

        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        label.text = holder.itemView.context.getString(
            R.string.v3_expand_all_pages_title, hiddenCount
        )
        // Press-down feedback — scale on ACTION_DOWN, restore on release/cancel.
        // Return false so the click still fires normally via setOnClickListener.
        pill.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(120)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }
        pill.setOnClickListener {
            overlay.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    overlay.visibility = View.GONE
                    expand()
                }
                .start()
        }
    }

    /**
     * Cache the overlay's 3 child views on the holder's itemView via setTag —
     * avoids repeated findViewById on each bind/payload rebind.
     */
    private fun overlayOf(holder: ViewHolder<RecyIllustDetailBinding>): Triple<FrameLayout, View, TextView>? {
        val root = holder.itemView
        @Suppress("UNCHECKED_CAST")
        (root.getTag(TAG_OVERLAY) as? Triple<FrameLayout, View, TextView>)?.let { return it }
        val overlay = root.findViewById<FrameLayout>(R.id.expand_overlay) ?: return null
        val pill = root.findViewById<View>(R.id.expand_pill) ?: return null
        val label = root.findViewById<TextView>(R.id.expand_label) ?: return null
        val triple = Triple(overlay, pill, label)
        root.setTag(TAG_OVERLAY, triple)
        return triple
    }

    override fun onViewAttachedToWindow(holder: ViewHolder<RecyIllustDetailBinding>) {
        super.onViewAttachedToWindow(holder)
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
            lp.isFullSpan = true
            holder.itemView.layoutParams = lp
        }
    }

    companion object {
        /** How many pages to show before collapsing. */
        const val DEFAULT_COLLAPSED = 1

        /** 1P and 2P are always shown in full; 3P and up get collapsed. */
        fun shouldCollapse(pageCount: Int, collapsedCount: Int = DEFAULT_COLLAPSED): Boolean {
            return pageCount > 2
        }

        private val PAYLOAD_OVERLAY_ONLY = Any()
        private val TAG_OVERLAY = R.id.expand_overlay
    }
}
