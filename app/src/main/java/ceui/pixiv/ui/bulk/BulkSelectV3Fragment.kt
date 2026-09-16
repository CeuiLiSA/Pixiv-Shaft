package ceui.pixiv.ui.bulk

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.ui.common.tintMenuIconsWhite
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.download.DownloadExportLinks
import ceui.pixiv.ui.download.originalUrlsOf
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.blankj.utilcode.util.BarUtils
import com.bumptech.glide.Glide
import com.hjq.toast.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ceui.pixiv.ui.navigation.TemplateRoute

/** 批量选择页的交接数据缓存，跨旋转保留，避免重复 take 导致列表空态。 */
private class BulkSelectRequestViewModel : ViewModel() {
    var source: List<Illust>? = null
    var items: List<SelectableItem>? = null
}

/**
 * V3 风格批量操作 · 多选页。
 *
 * 入口：IAdapter / TagAdapter 长按 → MultiDownload.startDownload() → TemplateActivity("批量选择") →
 *       本 fragment 用 arguments 里的 key 从 [IllustBulkSelectHandoff] take 出列表
 *
 * 行为：
 *  - 默认全不选（issue #922：默认全选时整屏铺蓝 scrim，缩略图反而看不清；想要全部的
 *    走 toolbar「全选」一下即可）
 *  - 全选 / 反选 在 toolbar 右上 menu（不再占用底部 bar）
 *  - 选中态：粗 v3_blue 边框 + 实色圆形勾标 + 微缩小 0.94，三层视觉差
 *  - 底部是 MD3-E connected button group（见 fragment_bulk_select_v3.xml）：
 *    · 首段（filled）：把选中的灌入 download_queue（走 LegacyBatchEnqueue），完成后跳转
 *      "下载管理" V3 总览页让用户看到入队进度，然后 finish 当前页
 *    · 尾段（tonal）：批量收藏 / 批量取消收藏（issue #974），走 PixivActions 门面的批量入口
 *      → `:actionqueue` 限流队列
 */
class BulkSelectV3Fragment : Fragment() {

    companion object {
        /** [handoffKey] 是入口 put 进 [IllustBulkSelectHandoff] 时拿到的 key；null / 过期时本页显示「没有可选项」。 */
        @JvmStatic
        fun newInstance(handoffKey: String?): BulkSelectV3Fragment = BulkSelectV3Fragment().apply {
            arguments = Bundle().apply { putString(BulkSelectHandoff.ARG_HANDOFF_KEY, handoffKey) }
        }
    }

    private val bulkViewModel by viewModels { BulkSelectRequestViewModel() }

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

    private lateinit var toolbar: Toolbar
    private lateinit var hint: TextView
    private lateinit var btnConfirm: Button
    private lateinit var btnBookmarkActions: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (bulkViewModel.source == null) {
            bulkViewModel.source = IllustBulkSelectHandoff.take(
                arguments?.getString(BulkSelectHandoff.ARG_HANDOFF_KEY)
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bulk_select_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().finish() }
        // 单按钮 master-checkbox 模式：icon 跟选中态切（refreshSelectToggleIcon），
        // 点击行为也跟 icon 一致 —— 没全选 → 全选；已全选 → 取消全选。
        // 反选已废，使用频率低 + 单按钮表达不出第三种状态。
        // 统一 toolbar 的 inset 打法(同 setUpToolbar(FragmentToolbarFeedBinding)):toolbar 自己
        // 顶到状态栏下面,底部导航 inset 作根布局 paddingBottom(原 fitsSystemWindows 的效果)。
        toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        toolbar.inflateMenu(R.menu.menu_bulk_select_v3)
        toolbar.tintMenuIconsWhite()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_toggle -> {
                    selectAllToggle()
                    true
                }
                R.id.action_export -> {
                    exportSelected()
                    true
                }
                else -> false
            }
        }

        hint = view.findViewById(R.id.hint)
        btnConfirm = view.findViewById(R.id.btnConfirm)
        btnBookmarkActions = view.findViewById(R.id.btnBookmarkActions)
        btnBookmarkActions.setOnClickListener { showBookmarkActionsMenu() }

        val grid = view.findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter

        // 取列表 —— 大列表（10000+）SelectableItem 构造也搬 IO 避免主线程长时间循环。
        // 只允许首次创建时 take；旋转重建从 ViewModel 恢复，避免重复 take 变成空态。
        val raw = bulkViewModel.source
        if (raw.isNullOrEmpty()) {
            hint.text = getString(R.string.bulk_select_no_items)
            btnConfirm.isEnabled = false
            btnConfirm.text = "—"
            setBookmarkActionsEnabled(false)
            // 没东西可选，菜单也禁用了避免误导
            toolbar.menu.findItem(R.id.action_select_toggle)?.isEnabled = false
            toolbar.menu.findItem(R.id.action_export)?.isEnabled = false
            return
        }
        hint.text = getString(R.string.bulk_select_loading)
        btnConfirm.isEnabled = false
        setBookmarkActionsEnabled(false)

        val restored = bulkViewModel.items
        if (restored != null) {
            items.clear()
            items.addAll(restored)
            bulkViewModel.items = items
            adapter.notifyDataSetChanged()
            refreshHeaderAndCta()
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val prepared = withContext(Dispatchers.IO) {
                    raw.map { illust ->
                        // ugoira 现在也走批量队列（独立 consumer 管线），不再 disable。
                        // 默认不选（issue #922），用户点选要的，或 toolbar 一键全选。
                        SelectableItem(illust, selected = false, selectable = true)
                    }
                }
                items.clear()
                items.addAll(prepared)
                bulkViewModel.items = items
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
                    // 入队完成立刻跳转下载管理 V3 总览，让用户能看到队列动起来 ——
                    // 否则用户点完确认眼前一黑（finish）只看到 toast，不知道东西去哪了。
                    val intent = Intent(ctx, TemplateActivity::class.java)
                        .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DOWNLOAD_MANAGER.key) // route key, not UI text
                    ctx.startActivity(intent)
                }
                requireActivity().finish()
            }
        }
    }

    /**
     * 导出当前已勾选作品**每张图的 original 直链**——多 P 一个 illust 占多行
     * （每页一行）。跟 confirm 按钮的"下载"语义对称：勾完之后先导链接备查，
     * 再点确认下载，事后用 .txt 给第三方下载器/IDM 对账或重抓缺漏页。
     *
     * Illust 在内存里就有 meta_pages / meta_single_page —— 用户在
     * 上层列表浏览时就抓到了，不需要二次反序列化。flatMap 走 IO 防卡帧
     * （列表上限 10000+，每个再产 N 个 url，主线程会感知）。
     */
    private fun exportSelected() {
        val snapshot = items.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            val urls = withContext(Dispatchers.IO) {
                snapshot.asSequence()
                    .filter { it.selected && it.selectable }
                    .flatMap { originalUrlsOf(it.illust).asSequence() }
                    .toList()
            }
            DownloadExportLinks.present(this@BulkSelectV3Fragment, urls)
        }
    }

    /** 当前勾选的 legacy bean 快照。收藏链路读的就是这份可变共享实例。 */
    private fun selectedBeans(): List<Illust> =
        items.filter { it.selected && it.selectable }.map { it.illust }

    /**
     * 底栏尾段的 V3 菜单：批量收藏 / 批量取消收藏（issue #974）。
     *
     * 两项都带**真正会发出去的条数**（已经是目标态的项由
     * [PixivActions.pendingIllustBookmarkCount] 剔掉），而不是勾选数 —— 勾了 200 项其中
     * 190 项本来就收藏着的话，「批量收藏 (200 项)」是句假话，用户会照着它去等一个不会
     * 发生的进度。
     */
    private fun showBookmarkActionsMenu() {
        val picked = selectedBeans()
        if (picked.isEmpty()) return
        val toBookmark = PixivActions.pendingIllustBookmarkCount(picked, bookmark = true)
        val toUnbookmark = PixivActions.pendingIllustBookmarkCount(picked, bookmark = false)
        val restrict = PixivActions.defaultBookmarkRestrict()
        val isPrivate = restrict == Params.TYPE_PRIVATE
        val addLabel = getString(
            if (isPrivate) R.string.bulk_bookmark_menu_add_private
            else R.string.bulk_bookmark_menu_add,
            toBookmark,
        )
        showV3Menu("BulkBookmarkMenu") {
            item(addLabel, R.drawable.ic_like_heart_fill) {
                confirmBookmark(picked, toBookmark, restrict, isPrivate)
            }
            item(
                getString(R.string.bulk_bookmark_menu_remove, toUnbookmark),
                R.drawable.ic_like_heart_outline,
            ) {
                confirmUnbookmark(picked, toUnbookmark)
            }
        }
    }

    /**
     * 收藏前的确认框。要讲清三件用户看不见但影响很大的事：会收进公开还是私密（跟随「私密收藏」
     * 设置，和单张爱心同一个判据）、请求是排队逐条发的、以及全部发完大概要多久 —— 队列按
     * 最小间隔串行，勾几百项就是十几分钟，不说清楚会被当成没生效。
     *
     * 「收藏后自动关注作者」开着时额外补一句：那会让未关注的作者一并进关注队列，
     * 单张收藏时这是一次不起眼的副作用，批量时却是一次性关注几十上百个人。
     */
    private fun confirmBookmark(
        picked: List<Illust>,
        count: Int,
        restrict: String,
        isPrivate: Boolean,
    ) {
        if (count == 0) {
            Toaster.showShort(R.string.bulk_bookmark_nothing)
            return
        }
        val ctx = context ?: return
        val restrictLabel = getString(
            if (isPrivate) R.string.bulk_bookmark_restrict_private
            else R.string.bulk_bookmark_restrict_public
        )
        val message = StringBuilder(
            getString(
                R.string.bulk_bookmark_confirm_message,
                count,
                restrictLabel,
                PixivActions.estimatedQueueMinutes(count),
            )
        )
        if (Shaft.sSettings.isAutoFollowAfterStar) {
            message.append(getString(R.string.bulk_bookmark_confirm_autofollow))
        }
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.bulk_bookmark_confirm_title)
            .setMessage(message.toString())
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.bulk_bookmark_confirm_go, WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                d.dismiss()
                enqueueBookmarks(picked, bookmark = true, restrict = restrict)
                // WitDialog 拿的是 Activity context、不跟 fragment 生命周期绑定，
                // 点到这里时 fragment 可能已经 detach（requireActivity() 会抛）。
                // 入队本身已经交给进程级 scope，收不收得到这个 finish 都不影响它。
                activity?.finish()
            }
            .create()
            .show()
    }

    /** 取消收藏的确认框。这一支是删数据且没有撤销，所以按钮用 NEGATIVE 语义。 */
    private fun confirmUnbookmark(picked: List<Illust>, count: Int) {
        if (count == 0) {
            Toaster.showShort(R.string.bulk_bookmark_nothing)
            return
        }
        val ctx = context ?: return
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.bulk_unbookmark_confirm_title)
            .setMessage(
                getString(
                    R.string.bulk_unbookmark_confirm_message,
                    count,
                    PixivActions.estimatedQueueMinutes(count),
                )
            )
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.bulk_bookmark_confirm_go, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                // restrict 对取消收藏无意义（delete 端点不带），传默认值占位。
                enqueueBookmarks(
                    picked, bookmark = false, restrict = PixivActions.defaultBookmarkRestrict(),
                )
                // WitDialog 拿的是 Activity context、不跟 fragment 生命周期绑定，
                // 点到这里时 fragment 可能已经 detach（requireActivity() 会抛）。
                // 入队本身已经交给进程级 scope，收不收得到这个 finish 都不影响它。
                activity?.finish()
            }
            .create()
            .show()
    }

    /**
     * 门面负责入队，这里只把它返回的**实际入队条数**报给用户。
     *
     * 报的是返回值而不是确认框上那个数：两者之间隔着一次用户点击，期间队列可能刚好回滚了
     * 某条失败的收藏，实际要发的条数就变了。
     */
    private fun enqueueBookmarks(picked: List<Illust>, bookmark: Boolean, restrict: String) {
        val enqueued = PixivActions.setIllustBookmarks(picked, bookmark, restrict)
        if (enqueued == 0) {
            Toaster.showShort(R.string.bulk_bookmark_nothing)
            return
        }
        val template =
            if (bookmark) R.string.bulk_bookmark_enqueued else R.string.bulk_unbookmark_enqueued
        Toaster.showShort(getString(template, enqueued))
    }

    /**
     * 尾段的禁用态。它是个 LinearLayout，[View.setEnabled] 只会把自己的
     * background selector 切到 disabled 那支，里面两个 ImageView 的 tint 不跟着走 ——
     * 所以另外压一层 alpha，让图标也一起暗下去。
     */
    private fun setBookmarkActionsEnabled(enabled: Boolean) {
        btnBookmarkActions.isEnabled = enabled
        btnBookmarkActions.alpha = if (enabled) 1f else 0.4f
    }

    private fun selectAllToggle() {
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

    private fun refreshHeaderAndCta() {
        val total = items.size
        val selected = items.count { it.selected && it.selectable }
        val gifSkipped = items.count { !it.selectable }
        // 求和已选作品的页数 —— 每个 illust 的 page_count 累加；page_count<=0 当 1 算
        val selectedImageCount = items.asSequence()
            .filter { it.selected && it.selectable }
            .sumOf { (it.illust.page_count.takeIf { c -> c > 0 } ?: 1) }
        hint.text = if (gifSkipped > 0) {
            getString(R.string.bulk_select_summary_with_gif, total, selected, selectedImageCount, gifSkipped)
        } else {
            getString(R.string.bulk_select_summary, total, selected, selectedImageCount)
        }
        btnConfirm.isEnabled = selected > 0
        btnConfirm.text = if (selected > 0) {
            getString(R.string.bulk_select_confirm, selected, selectedImageCount)
        } else {
            getString(R.string.bulk_select_confirm_empty)
        }
        // 导出按钮、收藏动作都跟 confirm 同步：没勾选 → 没东西可做
        toolbar.menu.findItem(R.id.action_export)?.isEnabled = selected > 0
        setBookmarkActionsEnabled(selected > 0)
        refreshSelectToggleIcon(selected)
    }

    /**
     * Master-checkbox 模式：根据"可选项有多少已选中"切 toolbar 单按钮的 icon + title。
     *  - 没全选（含部分选中 / 全没选） → ic_select_all_24，title="全选"
     *  - 已全选（可选项全选中）       → ic_deselect_24，title="取消全选"
     * 状态不仅是视觉信号，也跟 selectAllToggle 行为一致：用户看到啥 icon 就预期点击会做啥。
     */
    private fun refreshSelectToggleIcon(selectedCount: Int) {
        val item = toolbar.menu.findItem(R.id.action_select_toggle) ?: return
        val selectableCount = items.count { it.selectable }
        val allSelected = selectableCount > 0 && selectedCount == selectableCount
        item.setIcon(if (allSelected) R.drawable.ic_deselect_24 else R.drawable.ic_select_all_24)
        item.setTitle(if (allSelected) R.string.bulk_select_clear_all else R.string.bulk_select_select_all)
        // setIcon 后 MenuItem 的 iconTintList 不变,统一白色 tint 不需要重设。
    }
}

private data class SelectableItem(
    val illust: Illust,
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

        // GIF 徽章（不可选项才显示，跟 checkBadge 互斥）
        h.gifBadge.visibility = if (illust.isGif()) View.VISIBLE else View.GONE

        // —— 选中态：边框 + 圆形勾标 + 微缩小 ——
        val isSelected = item.selected && item.selectable
        h.selectedBorder.visibility = if (isSelected) View.VISIBLE else View.GONE
        h.checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
        // 微缩小 0.94 让选中项相对未选明显"凹"进去；非选中保持 1.0
        val targetScale = if (isSelected) SELECTED_SCALE else 1.0f
        h.itemView.scaleX = targetScale
        h.itemView.scaleY = targetScale

        // 不可选项视觉弱化
        h.itemView.alpha = if (item.selectable) 1f else 0.45f

        h.itemView.setOnClickListener { onToggle(h.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val pBadge: TextView = v.findViewById(R.id.pBadge)
        val gifBadge: TextView = v.findViewById(R.id.gifBadge)
        val selectedBorder: View = v.findViewById(R.id.selectedBorder)
        val checkBadge: ImageView = v.findViewById(R.id.checkBadge)
    }

    companion object {
        /** 选中时整张卡缩到 94% —— 让选中项明显"凹"进去，跟未选的 100% 形成对比 */
        private const val SELECTED_SCALE = 0.94f
    }
}
