package ceui.pixiv.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellV3AuthorWorkBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import com.bumptech.glide.Glide

class AuthorWorksAdapter(
    private val onClickWork: (Illust) -> Unit
) : RecyclerView.Adapter<AuthorWorksAdapter.VH>() {

    private val items = mutableListOf<Illust>()

    fun submitList(list: List<Illust>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellV3AuthorWorkBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: CellV3AuthorWorkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(illust: Illust) {
            val url = illust.image_urls?.square_medium
                ?: illust.image_urls?.medium
            if (url != null) {
                Glide.with(binding.workImage)
                    .load(GlideUrlChild(url))
                    .placeholder(R.drawable.bg_loading_placeholder)
                    .centerCrop()
                    .into(binding.workImage)
            }

            binding.workTitle.text = illust.title

            binding.pagesBadge.isVisible = illust.page_count > 1
            if (illust.page_count > 1) {
                binding.pagesBadge.text = "${illust.page_count}P"
            }

            binding.aiDot.isVisible = illust.illust_ai_type == 2

            binding.root.setOnClickListener { onClickWork(illust) }
        }
    }
}
