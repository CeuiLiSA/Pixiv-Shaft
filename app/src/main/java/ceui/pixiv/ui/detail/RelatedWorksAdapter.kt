package ceui.pixiv.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellV3RelatedWorkBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import com.bumptech.glide.Glide

class RelatedWorksAdapter(
    private val onClickWork: (Illust) -> Unit,
    private val onClickBookmark: (Illust, Int) -> Unit
) : RecyclerView.Adapter<RelatedWorksAdapter.VH>() {

    private val items = mutableListOf<Illust>()

    fun submitList(list: List<Illust>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun appendList(list: List<Illust>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellV3RelatedWorkBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position)
    }

    inner class VH(private val binding: CellV3RelatedWorkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(illust: Illust, position: Int) {
            // Staggered aspect ratio: odd=3:4, even=1:1
            val imageWrap = binding.imageWrap
            val lp = imageWrap.layoutParams
            if (lp is ConstraintLayout.LayoutParams) {
                lp.dimensionRatio = if (position % 2 == 0) "H,3:4" else "H,1:1"
                imageWrap.layoutParams = lp
            } else {
                // For non-ConstraintLayout parents, set height manually
                val width = imageWrap.context.resources.displayMetrics.widthPixels / 2 - 30
                val height = if (position % 2 == 0) (width * 4f / 3f).toInt() else width
                lp.height = height
                imageWrap.layoutParams = lp
            }

            val url = illust.image_urls?.square_medium
                ?: illust.image_urls?.medium
            if (url != null) {
                Glide.with(binding.relatedImage)
                    .load(GlideUrlChild(url))
                    .placeholder(R.drawable.bg_loading_placeholder)
                    .centerCrop()
                    .into(binding.relatedImage)
            }

            binding.relatedTitle.text = illust.title
            binding.relatedAuthorName.text = illust.user?.name

            val avatarUrl = illust.user?.profile_image_urls?.medium
                ?: illust.user?.profile_image_urls?.square_medium
            if (avatarUrl != null) {
                Glide.with(binding.relatedAuthorAvatar)
                    .load(GlideUrlChild(avatarUrl))
                    .circleCrop()
                    .into(binding.relatedAuthorAvatar)
                binding.relatedAuthorAvatar.isVisible = true
            } else {
                binding.relatedAuthorAvatar.isVisible = false
            }

            // Bookmark state
            updateBookmarkIcon(illust.is_bookmarked == true)

            binding.bookmarkBtn.setOnClickListener {
                onClickBookmark(illust, bindingAdapterPosition)
            }

            binding.root.setOnClickListener { onClickWork(illust) }
        }

        private fun updateBookmarkIcon(bookmarked: Boolean) {
            if (bookmarked) {
                binding.bookmarkBtn.setImageResource(R.drawable.ic_favorite_black_24dp)
                binding.bookmarkBtn.setColorFilter(
                    binding.root.context.getColor(R.color.v3_gold)
                )
            } else {
                binding.bookmarkBtn.setImageResource(R.drawable.ic_favorite_border_black_24dp)
                binding.bookmarkBtn.setColorFilter(
                    0xB3FFFFFF.toInt()
                )
            }
        }
    }
}
