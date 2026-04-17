package ceui.pixiv.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.databinding.SectionV3LoadingMoreBinding

class LoadingFooterAdapter : RecyclerView.Adapter<LoadingFooterAdapter.VH>() {

    private var visible = false

    fun show() {
        if (!visible) {
            visible = true
            notifyItemInserted(0)
        }
    }

    fun hide() {
        if (visible) {
            visible = false
            notifyItemRemoved(0)
        }
    }

    override fun getItemCount() = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(SectionV3LoadingMoreBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {}

    override fun onViewAttachedToWindow(holder: VH) {
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams) {
            lp.isFullSpan = true
        }
    }

    class VH(b: SectionV3LoadingMoreBinding) : RecyclerView.ViewHolder(b.root)
}
