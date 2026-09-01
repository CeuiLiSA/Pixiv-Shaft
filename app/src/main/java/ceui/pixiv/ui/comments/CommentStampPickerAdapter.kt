package ceui.pixiv.ui.comments

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellCommentStampBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.api.model.Stamp
import com.bumptech.glide.Glide

/**
 * 评论输入框「表情贴图」选择面板:官方常驻的 40 个插画贴纸(见 [StampCatalog]),点击直接
 * 单发一条纯贴纸评论,不经过输入框文字(对齐官方 App 抓包行为)。图来自远程 stamp_url,
 * 统一走 [GlideUrlChild] 收口(见 project_glide_okhttp_leak_fix)。
 */
class CommentStampPickerAdapter(
    private val onPick: (Stamp) -> Unit,
) : RecyclerView.Adapter<CommentStampPickerAdapter.VH>() {

    private var items: List<Stamp> = emptyList()

    fun submit(stamps: List<Stamp>) {
        items = stamps
        notifyDataSetChanged()
    }

    class VH(val binding: CellCommentStampBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellCommentStampBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        Glide.with(holder.binding.stampImage)
            .load(GlideUrlChild(item.stamp_url))
            .placeholder(R.drawable.bg_loading_placeholder)
            .into(holder.binding.stampImage)
        holder.itemView.setOnClickListener { onPick(item) }
    }

    override fun getItemCount(): Int = items.size
}
