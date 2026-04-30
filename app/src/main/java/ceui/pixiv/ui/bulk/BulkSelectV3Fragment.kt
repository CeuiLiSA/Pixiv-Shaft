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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // 取列表 —— 大列表（10000+）SelectableItem 构造也搬 IO 避免主线程长时间循环。
        val raw = BulkSelectStorage.consume()
        if (raw.isNullOrEmpty()) {
            hint.text = getString(R.string.bulk_select_no_items)
            btnConfirm.isEnabled = false
            btnConfirm.text = "—"
            return
        }
        hint.text = getString(R.string.bulk_select_loading)
        btnConfirm.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                raw.map { illust ->
                    val selectable = !illust.isGif
                    SelectableItem(illust, selected = selectable, selectable = selectable)
                }
            }
            items.clear()
            items.addAll(prepared)
            adapter.notifyDataSetChanged()
            refreshHeaderAndCta()
        }

        view.findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            // 大列表（10000+）的 N 次 copy 移到 IO，避免 5-20ms 主线程停顿
            val anyUnselected = items.any { it.selectable && !it.selected }
            val target = anyUnselected // 有未选 → 全选；否则 → 全不选
            viewLifecycleOwner.lifecycleScope.launch {
                val rebuilt = withContext(Dispatchers.IO) {
                    items.map { if (it.selectable) it.copy(selected = target) else it }
                }
                items.clear()
                items.addAll(rebuilt)
                adapter.notifyDataSetChanged()
                refreshHeaderAndCta()
            }
        }
        view.findViewById<Button>(R.id.btnInvert).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val rebuilt = withContext(Dispatchers.IO) {
                    items.map { if (it.selectable) it.copy(selected = !it.selected) else it }
                }
                items.clear()
                items.addAll(rebuilt)
                adapter.notifyDataSetChanged()
                refreshHeaderAndCta()
            }
        }
        btnConfirm.setOnClickListener {
            // 大列表（10000+）的 filter/map 也走 IO 防卡帧；快照后立刻禁用按钮防双击。
            btnConfirm.isEnabled = false
            val snapshot = items.toList()
            val ctx = requireContext()
            viewLifecycleOwner.lifecycleScope.launch {
                val picked = withContext(Dispatchers.IO) {
                    snapshot.asSequence()
                        .filter { it.selected && it.selectable }
                        .map { it.illust }
                        .toList()
                }
                if (picked.isNotEmpty()) {
                    LegacyBatchEnqueue.enqueueAndToast(ctx, picked)
                }
                requireActivity().finish()
            }
        }
    }

    private fun refreshHeaderAndCta() {
        val total = items.size
        val selected = items.count { it.selected && it.selectable }
        val gifSkipped = items.count { !it.selectable }
        hint.text = if (gifSkipped > 0) {
            getString(R.string.bulk_select_summary_with_gif, total, selected, gifSkipped)
        } else {
            getString(R.string.bulk_select_summary, total, selected)
        }
        btnConfirm.isEnabled = selected > 0
        btnConfirm.text = if (selected > 0) {
            getString(R.string.bulk_select_confirm, selected)
        } else {
            getString(R.string.bulk_select_confirm_empty)
        }
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
