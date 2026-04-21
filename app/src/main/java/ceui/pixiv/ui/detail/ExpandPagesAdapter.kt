package ceui.pixiv.ui.detail

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.SectionV3ExpandPagesBinding
import ceui.pixiv.utils.ppppx

/**
 * Single-item adapter rendering the "展开剩余 N 张" CTA card shown between the
 * collapsed IllustAdapter and the V3 info sections. Tap expands the IllustAdapter
 * and hides this adapter.
 */
class ExpandPagesAdapter(
    private val hiddenCount: Int,
    private val onExpand: () -> Unit
) : RecyclerView.Adapter<ExpandPagesAdapter.VH>() {

    private var visible = hiddenCount > 0

    fun hide() {
        if (visible) {
            visible = false
            notifyItemRemoved(0)
        }
    }

    override fun getItemCount() = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            SectionV3ExpandPagesBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.itemView.context
        holder.binding.expandTitle.text =
            ctx.getString(R.string.v3_expand_all_pages_title, hiddenCount)
        holder.itemView.setOnClickListener {
            // Small tactile feedback then expand.
            it.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    onExpand()
                }.start()
        }
    }

    override fun onViewAttachedToWindow(holder: VH) {
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
            lp.isFullSpan = true
            holder.itemView.layoutParams = lp
        }
        // Looping chevron bob to draw the eye.
        holder.stopChevron()
        val bob = 6.ppppx.toFloat()
        holder.chevronAnimator = ObjectAnimator.ofFloat(
            holder.binding.expandChevron, "translationY", 0f, bob, 0f
        ).apply {
            duration = 1400
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        holder.stopChevron()
    }

    class VH(val binding: SectionV3ExpandPagesBinding) : RecyclerView.ViewHolder(binding.root) {
        var chevronAnimator: ObjectAnimator? = null

        fun stopChevron() {
            chevronAnimator?.cancel()
            chevronAnimator = null
            binding.expandChevron.translationY = 0f
        }
    }
}
