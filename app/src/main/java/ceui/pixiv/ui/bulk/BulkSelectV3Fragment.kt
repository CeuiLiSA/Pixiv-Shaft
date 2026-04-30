package ceui.pixiv.ui.bulk

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide

/**
 * V3 风格批量下载 · 多选页。
 *
 * 入口：IAdapter / TagAdapter 长按 → MultiDownload.startDownload() → TemplateActivity("批量选择") →
 *       本 fragment 取 BulkSelectStorage.consume() 拿到列表
 *
 * 行为：
 *  - 默认全选（除 GIF —— GIF 走单独 ugoira 管线，本队列不接）
 *  - 全选 / 反选 按钮
 *  - 确认按钮：把选中的灌入 download_queue（走 LegacyBatchEnqueue），完成后 finish
 */
class BulkSelectV3Fragment : Fragment() {

    private val items = mutableListOf<SelectableItem>()
    private val adapter: BulkSelectAdapter by lazy {
        BulkSelectAdapter(items) { pos -> toggleAt(pos) }
    }

    private fun toggleAt(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        if (!items[pos].selectable) return
        items[pos] = items[pos].copy(selected = !items[pos].selected)
        adapter.notifyItemChanged(pos)
        refreshHeaderAndCta()
    }

    private lateinit var hint: TextView
    private lateinit var btnConfirm: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bulk_select_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().finish()
        }
        hint = view.findViewById(R.id.hint)
        btnConfirm = view.findViewById(R.id.btnConfirm)

        val grid = view.findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter

        // 取列表
        val list = BulkSelectStorage.consume()
        if (list.isNullOrEmpty()) {
            hint.text = "没有可选作品，可能从其他入口进来导致状态丢失。"
            btnConfirm.isEnabled = false
            btnConfirm.text = "—"
            return
        }
        items.clear()
        list.forEach { illust ->
            // GIF 不可选（队列不支持），其它默认选中
            val selectable = !illust.isGif
            items.add(SelectableItem(illust, selected = selectable, selectable = selectable))
        }
        adapter.notifyDataSetChanged()
        refreshHeaderAndCta()

        view.findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            val anyUnselected = items.any { it.selectable && !it.selected }
            for (i in items.indices) {
                if (!items[i].selectable) continue
                items[i] = items[i].copy(selected = anyUnselected) // 有未选 → 全选；否则 → 全不选
            }
            adapter.notifyDataSetChanged()
            refreshHeaderAndCta()
        }
        view.findViewById<Button>(R.id.btnInvert).setOnClickListener {
            for (i in items.indices) {
                if (!items[i].selectable) continue
                items[i] = items[i].copy(selected = !items[i].selected)
            }
            adapter.notifyDataSetChanged()
            refreshHeaderAndCta()
        }
        btnConfirm.setOnClickListener {
            val picked = items.filter { it.selected && it.selectable }.map { it.illust }
            if (picked.isEmpty()) return@setOnClickListener
            LegacyBatchEnqueue.enqueueAndToast(requireContext(), picked)
            requireActivity().finish()
        }
    }

    private fun refreshHeaderAndCta() {
        val total = items.size
        val selected = items.count { it.selected && it.selectable }
        val gifSkipped = items.count { !it.selectable }
        hint.text = if (gifSkipped > 0) {
            "共 $total 项 · 已选 $selected 项 · GIF 已跳过 $gifSkipped 项"
        } else {
            "共 $total 项 · 已选 $selected 项"
        }
        btnConfirm.isEnabled = selected > 0
        btnConfirm.text = if (selected > 0) "加入下载队列 ($selected) →" else "请至少选择 1 项"
    }
}

private data class SelectableItem(
    val illust: IllustsBean,
    val selected: Boolean,
    val selectable: Boolean,
)

private class BulkSelectAdapter(
    private val items: List<SelectableItem>,
    private val onToggle: (position: Int) -> Unit,
) : RecyclerView.Adapter<BulkSelectAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_bulk_select_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val illust = item.illust

        // 缩略图
        val showUrl = runCatching { illust.image_urls?.medium }.getOrNull()
        if (!showUrl.isNullOrEmpty()) {
            Glide.with(h.thumb).load(GlideUtil.getUrl(showUrl))
                .placeholder(android.R.color.transparent).into(h.thumb)
        } else {
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
        }

        // 多页徽章
        val pageCount = illust.page_count
        if (pageCount > 1) {
            h.pBadge.visibility = View.VISIBLE
            h.pBadge.text = "${pageCount}P"
        } else {
            h.pBadge.visibility = View.GONE
        }

        // GIF 徽章
        h.gifBadge.visibility = if (illust.isGif) View.VISIBLE else View.GONE

        // 选中状态
        val show = item.selected && item.selectable
        h.selectOverlay.visibility = if (show) View.VISIBLE else View.GONE
        h.checkMark.visibility = if (show) View.VISIBLE else View.GONE

        // 不可选项视觉弱化
        h.itemView.alpha = if (item.selectable) 1f else 0.45f

        h.itemView.setOnClickListener { onToggle(h.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val pBadge: TextView = v.findViewById(R.id.pBadge)
        val gifBadge: TextView = v.findViewById(R.id.gifBadge)
        val selectOverlay: View = v.findViewById(R.id.selectOverlay)
        val checkMark: ImageView = v.findViewById(R.id.checkMark)
    }
}
