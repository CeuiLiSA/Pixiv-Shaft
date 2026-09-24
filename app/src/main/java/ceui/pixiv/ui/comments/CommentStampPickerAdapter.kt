package ceui.pixiv.ui.comments

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellCommentStampBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.api.model.Stamp
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.Glide

/**
 * 评论输入框「表情贴图」选择面板:官方常驻的 40 个插画贴纸(见 [StampCatalog])。
 *
 * 两步发送(#1147,学 LINE):第一次点只选中——主题色描边 + 右上角纸飞机;再点同一张才单发一条
 * 纯贴纸评论(不经过输入框文字,对齐官方 App 抓包行为)。评论是公开挂在别人作品下的,误发的
 * 代价远高于多点一下。图来自远程 stamp_url,统一走 [GlideUrlChild] 收口(见 project_glide_okhttp_leak_fix)。
 */
class CommentStampPickerAdapter(
    private val palette: V3Palette,
    private val onSend: (Stamp) -> Unit,
) : RecyclerView.Adapter<CommentStampPickerAdapter.VH>() {

    private var items: List<Stamp> = emptyList()
    private var selectedStampId: Long? = null

    fun submit(stamps: List<Stamp>) {
        items = stamps
        selectedStampId = null
        notifyDataSetChanged()
    }

    /** 离开贴图页 / 收起面板 / 已发出时调用,回到「未选中」。 */
    fun clearSelection() {
        select(null)
    }

    private fun select(stampId: Long?) {
        val previous = selectedStampId
        if (previous == stampId) return
        selectedStampId = stampId
        notifyStampChanged(previous)
        notifyStampChanged(stampId)
    }

    private fun notifyStampChanged(stampId: Long?) {
        if (stampId == null) return
        val position = items.indexOfFirst { it.stamp_id == stampId }
        if (position >= 0) notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    class VH(val binding: CellCommentStampBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellCommentStampBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val density = parent.resources.displayMetrics.density
        binding.stampRing.background = GradientDrawable().apply {
            cornerRadius = RING_RADIUS_DP * density
            setStroke((RING_STROKE_DP * density).toInt(), palette.primary)
        }
        binding.stampSendBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.primary)
        }
        binding.stampSendBadge.setColorFilter(palette.onPrimary)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION }) {
            bindSelection(holder, items[position], animate = true)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        Glide.with(holder.binding.stampImage)
            .load(GlideUrlChild(item.stamp_url))
            .placeholder(R.drawable.bg_loading_placeholder)
            .into(holder.binding.stampImage)
        holder.itemView.setOnClickListener {
            if (selectedStampId == item.stamp_id) onSend(item) else select(item.stamp_id)
        }
        bindSelection(holder, item, animate = false)
    }

    private fun bindSelection(holder: VH, item: Stamp, animate: Boolean) {
        val selected = selectedStampId == item.stamp_id
        val target = if (selected) 1f else 0f
        val ring = holder.binding.stampRing
        val badge = holder.binding.stampSendBadge
        ring.animate().cancel()
        badge.animate().cancel()
        if (animate) {
            ring.animate().alpha(target).setDuration(SELECTION_ANIM_MS).start()
            badge.animate().alpha(target).setDuration(SELECTION_ANIM_MS).start()
        } else {
            ring.alpha = target
            badge.alpha = target
        }
        holder.itemView.isSelected = selected
        // 读屏:选中后「点按两次」的动作读作「发送」,而不是泛泛的「激活」。
        ViewCompat.replaceAccessibilityAction(
            holder.itemView,
            AccessibilityActionCompat.ACTION_CLICK,
            if (selected) holder.itemView.context.getString(R.string.plaza_send) else null,
            null,
        )
    }

    override fun getItemCount(): Int = items.size

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
        const val SELECTION_ANIM_MS = 180L
        const val RING_RADIUS_DP = 16f
        const val RING_STROKE_DP = 2f
    }
}
