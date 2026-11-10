package ceui.pixiv.snapshot

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.MessageQueue
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentSnapshotListBinding
import ceui.lisa.databinding.ItemSnapshotBinding
import ceui.lisa.fragments.HistorySelectBadge
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil
import ceui.lisa.view.SpacesItemDecoration
import ceui.pixiv.feeds.FeedStaggeredSkeletonView
import ceui.pixiv.ui.common.IllustLayoutSkeletonView
import ceui.pixiv.ui.common.IllustListLayout
import ceui.pixiv.ui.common.JustifiedItemDecoration
import ceui.pixiv.ui.common.JustifiedItemSource
import ceui.pixiv.ui.common.JustifiedLayoutManager
import ceui.pixiv.ui.common.scrollToPositionWithOffset
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 离线快照管理页的单个 Tab：跟随全局插画列表布局，按 manifest.type 过滤。
 * filter == null 表示“全部”。
 *
 * 列表由正式快照（SnapshotSummary）和自动快照（AutoSnapshotSummary）合并而成：
 * 自动快照带“自动”标记，长按弹窗可转正/删除；批量导出多选态不显示自动快照勾选框，
 * 批量删除多选态允许勾选并批量删除自动快照。
 */
class SnapshotListFragment : Fragment() {

    private var _binding: FragmentSnapshotListBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var listLayout = IllustListLayout.MASONRY
    private var prewarmCapacity = 0
    private var cancelPendingCardPrewarm: (() -> Unit)? = null
    private val cardPrewarmScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleCardPrewarm()
            else stopCardPrewarm()
        }
    }

    private val filter: String? by lazy(LazyThreadSafetyMode.NONE) {
        arguments?.getString(ARG_FILTER)
    }

    private val adapter = SnapshotAdapter(
        onOpen = { openSnapshot(it) },
        onExport = { exportSnapshot(it) },
        onDelete = { confirmDelete(it) },
        onLongPress = { onLongPress(it) },
    ).also { adapter ->
        adapter.onSelectionToggle = { onSelectionCountChanged?.invoke(adapter.selectedIds.size) }
    }

    /** 选中数量变化回调，由宿主 Tabs 页驱动 selection toolbar。 */
    var onSelectionCountChanged: ((Int) -> Unit)? = null

    /** 列表重新加载后回调，宿主用来重算占用条数值。 */
    var onAutoSnapshotQuotaChanged: (() -> Unit)? = null

    private var pendingExportId: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val id = pendingExportId ?: return@registerForActivityResult
        if (uri != null) {
            val appContext = requireContext().applicationContext
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { SnapshotRepository.export(appContext, id, uri) }
                    Common.showToast(getString(R.string.snapshot_export_success))
                } catch (e: Exception) {
                    Timber.w(e, "[Snapshot] export failed, id=%s", id)
                    Common.showToast(getString(R.string.snapshot_export_failed, e.message ?: ""))
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = FragmentSnapshotListBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 骨架默认可见，首次 reload 完成后收起；布局与卡片一起跟随设置。
        listLayout = IllustListLayout.current()
        installListLayout()
        val list = binding.snapshotList
        list.addOnScrollListener(cardPrewarmScrollListener)
        val skeletonHost = binding.snapshotSkeleton
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val manager = list.layoutManager as? StaggeredGridLayoutManager
            val skeleton = skeletonHost.getChildAt(0) as? FeedStaggeredSkeletonView
            if (manager != null && skeleton != null) skeleton.spanCount = manager.spanCount
        }
        reload(resetScroll = true)
    }

    override fun onResume() {
        super.onResume()
        val latest = IllustListLayout.current()
        if (latest != listLayout) {
            listLayout = latest
            installListLayout()
        }
        // 例行刷新:不清空、不回顶。看完一个快照返回时列表内容通常一模一样,
        // Diff 出来是零变更,滚动位置原样保留。
        reload()
        binding.snapshotList.doOnPreDraw { scheduleCardPrewarm() }
    }

    override fun onPause() {
        stopCardPrewarm()
        super.onPause()
    }

    /** 只在列表静止且消息队列空闲时补充复用池，避免慢滑时在 RV Prefetch 中现场 inflate。 */
    private fun scheduleCardPrewarm() {
        val list = _binding?.snapshotList ?: return
        if (!isResumed || !list.isAttachedToWindow ||
            list.scrollState != RecyclerView.SCROLL_STATE_IDLE) return

        val reserve = list.childCount.coerceIn(6, 24)
        if (reserve > prewarmCapacity) {
            prewarmCapacity = reserve
            list.recycledViewPool.setMaxRecycledViews(0, prewarmCapacity)
        }
        val pool = list.recycledViewPool
        if (pool.getRecycledViewCount(0) >= prewarmCapacity || cancelPendingCardPrewarm != null) return

        val queue = Looper.myQueue()
        val handler = MessageQueue.IdleHandler {
            if (_binding?.snapshotList !== list || !isResumed || !list.isAttachedToWindow ||
                list.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                cancelPendingCardPrewarm = null
                false
            } else if (pool.getRecycledViewCount(0) >= prewarmCapacity) {
                cancelPendingCardPrewarm = null
                false
            } else {
                // 经 Adapter.createViewHolder 设置正确的 viewType，pool 才能在预取时命中。
                pool.putRecycledView(adapter.createViewHolder(list, 0))
                val more = pool.getRecycledViewCount(0) < prewarmCapacity
                if (!more) cancelPendingCardPrewarm = null
                more
            }
        }
        queue.addIdleHandler(handler)
        cancelPendingCardPrewarm = { queue.removeIdleHandler(handler) }
    }

    private fun stopCardPrewarm() {
        cancelPendingCardPrewarm?.invoke()
        cancelPendingCardPrewarm = null
    }

    private fun installListLayout() {
        val list = binding.snapshotList
        val previous = list.layoutManager
        val anchor = when (previous) {
            is StaggeredGridLayoutManager ->
                previous.findFirstVisibleItemPositions(null).filter { it >= 0 }.minOrNull()
            is LinearLayoutManager -> previous.findFirstVisibleItemPosition().takeIf { it >= 0 }
            else -> null
        }
        val offset = anchor?.let { previous?.findViewByPosition(it)?.top?.minus(list.paddingTop) } ?: 0
        val space = DensityUtil.dp2px(8f)
        val manager = when (listLayout) {
            IllustListLayout.JUSTIFIED ->
                JustifiedLayoutManager(requireContext(), Shaft.sSettings.lineCount, space)
            IllustListLayout.SINGLE_COLUMN ->
                StaggeredManager(1, StaggeredGridLayoutManager.VERTICAL)
            IllustListLayout.MASONRY, IllustListLayout.GRID ->
                StaggeredManager.adaptive(requireContext(), Shaft.sSettings.lineCount)
        }

        // 与首页推荐流同源：列表和卡片不叠加边距，8dp 外缘与中缝都由 decoration 管。
        list.adapter = null
        while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)
        list.setPadding(0, 0, 0, 0)
        list.layoutManager = manager
        list.addItemDecoration(
            if (manager is JustifiedLayoutManager) JustifiedItemDecoration(manager)
            else SpacesItemDecoration(space)
        )
        adapter.layoutMode = listLayout
        list.adapter = adapter
        if (anchor != null) list.scrollToPositionWithOffset(anchor, offset)

        val skeleton = when (listLayout) {
            IllustListLayout.GRID ->
                IllustLayoutSkeletonView(requireContext(), listLayout, (manager as StaggeredGridLayoutManager).spanCount, space)
            IllustListLayout.JUSTIFIED ->
                IllustLayoutSkeletonView(requireContext(), listLayout, Shaft.sSettings.lineCount, space)
            IllustListLayout.MASONRY, IllustListLayout.SINGLE_COLUMN ->
                FeedStaggeredSkeletonView(requireContext()).apply {
                    spanCount = (manager as StaggeredGridLayoutManager).spanCount
                }
        }
        binding.snapshotSkeleton.removeAllViews()
        binding.snapshotSkeleton.addView(skeleton, ViewGroup.LayoutParams(-1, -1))
    }

    override fun onDestroyView() {
        stopCardPrewarm()
        binding.snapshotList.removeOnScrollListener(cardPrewarmScrollListener)
        prewarmCapacity = 0
        super.onDestroyView()
        _binding = null
    }

    /**
     * [resetScroll] 只在导入/批量删除这类**结构性变更**后传 true。
     * 单卡删除只移除一张卡，保留当前滚动位置即可，与浏览历史页行为一致。
     * [reflow] 用于单卡删除后让 StaggeredGrid 重新分配 span，避免最后一张停在右列；
     * 不会清空列表，也不会回顶。
     *
     * 三条路径都走**增量**提交（DiffUtil），不做「清空重填」：清空重填的 diff 是「全部 remove +
     * 全部 insert」，动画只能是整片淡出再整片淡入，而导入/删除要的是「新卡插进来、旧卡不动」。
     * 代价是清空重填顺带做的「新卡列分配推倒重来」没有了，新卡改由 SGLM 按当前锚点分配 —— 若看到
     * 新卡被塞进右列、或与旧列错位，第一件事是给这条路径补一次 invalidateSpanAssignments()。
     * [resetScroll] 的回顶排在提交回调里，且**只在本次结果比当前列表多（有新增）时**才做：导入的新卡排在顶部，不回顶看不到；删除不需要回顶，而设了 pending scroll 会让 SGLM 拿一个 -1 的锚点、整列表排不出卡片（详见下面的注释）。
     */
    fun reload(resetScroll: Boolean = false, reflow: Boolean = false) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val all = try {
                withContext(Dispatchers.IO) {
                    val root = SnapshotRepository.root(appContext)
                    val formal = SnapshotRepository.list(appContext).map { FormalSnapshotCard(it) }
                    val auto = AutoSnapshotRepository.listAuto(appContext).map { AutoSnapshotCard(it) }
                    (formal + auto)
                        .filter { filter == null || it.type == filter }
                        .sortedByDescending { it.createdAt }
                        // 过滤后再算：老清单要读 illust.json，只为本 Tab 真正展示的卡付这笔
                        .map { it.withCoverHeightRatio(root) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.w(e, "[Snapshot] list failed")
                _binding?.snapshotSkeleton?.isVisible = false
                return@launch
            }
            if (_binding == null) return@launch
            val snapshotList = binding.snapshotList
            // 提交是异步的：这一刻 adapter 还是旧列表，用它的长度判断「这次有没有新增」。
            val hadItems = adapter.itemCount
            adapter.submitList(all, Runnable {
                if (_binding?.snapshotList === snapshotList) {
                    if (reflow) {
                        // 只清「位置 → 列」的分配；各列自己的行缓存（Span.mCachedStart / mCachedEnd）它清不掉。
                        (snapshotList.layoutManager as? StaggeredGridLayoutManager)
                            ?.invalidateSpanAssignments()
                    }
                    // 有新增才回顶：导入的新卡排在顶部，不回顶看不到；删除保持当前位置不动。
                    //（删除路径设 pending scroll 会被 SGLM 的锚点计算写坏，见
                    // StaggeredManager#deferAnchorBreakingScroll。）
                    if (resetScroll && all.size > hadItems) {
                        resetStaggeredColumns(snapshotList)
                        snapshotList.scrollToPosition(0)
                    }
                    if (all.isNotEmpty()) {
                        snapshotList.doOnPreDraw { scheduleCardPrewarm() }
                    }
                }
            })
            binding.snapshotSkeleton.isVisible = false
            binding.emptyHint.isVisible = all.isEmpty()
            if (filter == null) onAutoSnapshotQuotaChanged?.invoke()
        }
    }

    /**
     * 回顶前按需把瀑布流的列状态归零。
     *
     * 位置 0 若仍是某个已附加、且已滑出顶部的旧卡（导入的卡片比现有卡片更旧时位置 0 不变），SGLM 会把
     * `paddingTop - getDecoratedStart(item0)`（正数）当锚点偏移写进各列参考行，让某一列拿到正的起排 y
     * —— 表现为某一列顶部空出一块。只有这种情形才需要重建列状态。
     *
     * 重建手段：`setSpanCount` 是 SGLM 唯一会 `new Span[]` 的公开口，抖一次列数即可把各列行缓存与
     * span 分配一起清零。动画进行中重建会让内部计数对不上（子视图被清空的空白屏），所以先摘掉
     * animator，下一帧再装回。
     */
    private fun resetStaggeredColumns(listView: RecyclerView) {
        val manager = listView.layoutManager as? StaggeredGridLayoutManager ?: return
        val first = manager.findViewByPosition(0)
        // 竖向 SGLM 里 getDecoratedStart(item0) 等于 getDecoratedTop(item0) - topMargin
        //（getDecoratedStart 是 OrientationHelper 的方法，LayoutManager 上没有）。
        val firstStart = first?.let {
            manager.getDecoratedTop(it) - ((it.layoutParams as? RecyclerView.LayoutParams)?.topMargin ?: 0)
        } ?: return
        if (firstStart >= listView.paddingTop) return
        val animator = listView.itemAnimator
        if (animator != null) {
            listView.itemAnimator = null
        }
        val spanCount = manager.spanCount
        manager.setSpanCount(spanCount + 1)
        manager.setSpanCount(spanCount)
        if (animator != null) {
            listView.post { listView.itemAnimator = animator }
        }
    }

    fun enterSelectionMode(includeAuto: Boolean = false) {
        if (!hasItems(includeAuto)) return
        adapter.setSelectionMode(true, includeAuto)
        onSelectionCountChanged?.invoke(0)
    }

    fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        onSelectionCountChanged?.invoke(0)
    }

    /** 批量导出只包含正式快照；自动快照不可导出，只参与批量删除。 */
    fun selectedSnapshots(): List<SnapshotSummary> =
        adapter.currentList
            .filterIsInstance<FormalSnapshotCard>()
            .filter { it.snapshotId in adapter.selectedIds }
            .map { it.summary }

    /** 批量删除可同时包含正式快照与自动快照。 */
    fun selectedCards(): List<SnapshotCard> =
        adapter.currentList.filter { it.snapshotId in adapter.selectedIds }

    fun selectedCount(): Int = adapter.selectedIds.size

    /**
     * 批量导出只对正式快照有效；批量删除允许自动快照，因此 [includeAuto] 时自动快照也算可选项。
     */
    fun hasItems(includeAuto: Boolean = false): Boolean =
        adapter.currentList.any { includeAuto || !it.isAuto }

    fun isAllSelected(): Boolean = adapter.isAllSelected()

    fun toggleSelectAll() {
        if (adapter.isAllSelected()) {
            adapter.clearSelection()
        } else {
            adapter.selectAll()
        }
        onSelectionCountChanged?.invoke(adapter.selectedIds.size)
    }

    private fun openSnapshot(card: SnapshotCard) {
        val snapshotId = card.snapshotId
        val isAuto = card.isAuto
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (SnapshotRuntimeCache.get(snapshotId) == null) {
                        val data = if (isAuto) {
                            AutoSnapshotRepository.loadAutoViewerData(appContext, snapshotId)
                        } else {
                            SnapshotRepository.loadViewerData(appContext, snapshotId)
                        }
                        SnapshotRuntimeCache.put(snapshotId, data)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.w(e, "[Snapshot] open failed, id=%s", snapshotId)
                Common.showToast(getString(R.string.snapshot_open_failed, e.message ?: ""))
                reload(resetScroll = true)
                return@launch
            }
            if (_binding == null) return@launch
            val intent = Intent(requireContext(), TemplateActivity::class.java)
            if (Shaft.sSettings.isUseArtworkV3()) {
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SNAPSHOT_VIEW.key)
            } else {
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SNAPSHOT_VIEW_CLASSIC.key)
            }
            intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_ID, snapshotId)
            intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, isAuto)
            startActivity(intent)
        }
    }

    private fun onLongPress(card: SnapshotCard) {
        when (card) {
            is AutoSnapshotCard -> showAutoActions(card)
            is FormalSnapshotCard -> exportSnapshot(card)
        }
    }

    private fun showAutoActions(card: AutoSnapshotCard) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(card.title ?: getString(R.string.snapshot_untitled))
            .addAction(R.string.snapshot_promote) { dialog, _ ->
                dialog.dismiss()
                confirmPromote(card)
            }
            .addAction(0, R.string.snapshot_delete, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                confirmDelete(card)
            }
            .show()
    }

    private fun confirmPromote(card: AutoSnapshotCard) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.snapshot_promote_title)
            .setMessage(R.string.snapshot_promote_confirm)
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(R.string.snapshot_promote) { dialog, _ ->
                dialog.dismiss()
                val appContext = requireContext().applicationContext
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            SnapshotPromoter.promote(appContext, card.snapshotId)
                        }
                        Common.showToast(getString(R.string.snapshot_promote_success))
                        reload()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Timber.w(e, "[Snapshot] promote failed, id=%s", card.snapshotId)
                        Common.showToast(getString(R.string.snapshot_promote_failed, e.message ?: ""))
                    }
                }
            }
            .show()
    }

    private fun exportSnapshot(card: SnapshotCard) {
        // 自动快照不允许导出，只有转正为 SnapshotManifest 后才可导出。
        if (card is AutoSnapshotCard) return
        val formal = card as FormalSnapshotCard
        pendingExportId = formal.snapshotId
        exportLauncher.launch(formal.summary.manifest.safeExportFileName())
    }

    private fun confirmDelete(card: SnapshotCard) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.snapshot_delete)
            .setMessage(getString(R.string.snapshot_delete_confirm, card.title ?: card.snapshotId))
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.snapshot_delete, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                val appContext = requireContext().applicationContext
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        if (card.isAuto) {
                            AutoSnapshotRepository.deleteAuto(appContext, card.snapshotId)
                        } else {
                            SnapshotRepository.delete(appContext, card.snapshotId)
                        }
                    }
                    if (ok) Common.showToast(getString(R.string.snapshot_delete_success)) else Common.showToast(getString(R.string.snapshot_delete_failed))
                    reload(reflow = true)
                }
            }
            .show()
    }

    companion object {
        private const val ARG_FILTER = "filter"

        fun newInstance(filter: String?): SnapshotListFragment {
            return SnapshotListFragment().apply {
                arguments = Bundle().apply {
                    if (filter != null) putString(ARG_FILTER, filter)
                }
            }
        }
    }
}

private class SnapshotAdapter(
    private val onOpen: (SnapshotCard) -> Unit,
    private val onExport: (SnapshotCard) -> Unit,
    private val onDelete: (SnapshotCard) -> Unit,
    private val onLongPress: (SnapshotCard) -> Unit,
) : ListAdapter<SnapshotCard, SnapshotViewHolder>(DIFF), JustifiedItemSource {

    var layoutMode: IllustListLayout = IllustListLayout.MASONRY

    override fun aspectRatioAt(position: Int): Float =
        1f / currentList[position].coverHeightRatio

    var selectionMode: Boolean = false
        private set

    var selectionIncludesAuto: Boolean = false
        private set

    val selectedIds = linkedSetOf<String>()
    var onSelectionToggle: ((SnapshotCard) -> Unit)? = null

    fun setSelectionMode(enabled: Boolean, includeAuto: Boolean = false) {
        if (selectionMode == enabled && selectionIncludesAuto == includeAuto) return
        selectionMode = enabled
        selectionIncludesAuto = if (enabled) includeAuto else false
        if (!enabled) selectedIds.clear()
        notifySelectionChanged()
    }

    fun toggleSelection(card: SnapshotCard) {
        if (!selectionMode || !isSelectable(card)) return
        if (!selectedIds.add(card.snapshotId)) {
            selectedIds.remove(card.snapshotId)
        }
        onSelectionToggle?.invoke(card)
        notifySelectionChanged()
    }

    fun selectAll() {
        if (!selectionMode) return
        selectedIds.clear()
        selectedIds.addAll(currentList.filter(::isSelectable).map { it.snapshotId })
        notifySelectionChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        notifySelectionChanged()
    }

    /**
     * 刷新所有条目的选中态。**不用 notifyDataSetChanged()**：
     * - 它会让 RecyclerView 把整表标成「数据集已变」（mDataSetHasChangedAfterLayout），于是这次
     *   布局会 mAdapterHelper.reset() 丢掉待处理的增量更新，而且本页没有 stable id，整趟都不跑动画；
     * - ListAdapter 的 AsyncListDiffer 并不知道这次外部通知，两边状态会脱节。
     * 也**不能不带 payload**：DefaultItemAnimator.canReuseUpdatedViewHolder 见到非空 payload 才返回
     * true，否则这次变更会被当成「内容变了」，整屏卡片一起跑 change 淡入淡出 —— 看起来就是
     * 「点一下选中，所有卡片的图都重播了一遍淡入」。
     */
    private fun notifySelectionChanged() {
        val count = itemCount
        if (count > 0) notifyItemRangeChanged(0, count, PAYLOAD_SELECTION)
    }


    private fun isSelectable(card: SnapshotCard): Boolean =
        !card.isAuto || selectionIncludesAuto

    fun isAllSelected(): Boolean {
        val selectable = currentList.count(::isSelectable)
        return selectable > 0 && selectedIds.size == selectable
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnapshotViewHolder {
        val binding = ItemSnapshotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SnapshotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SnapshotViewHolder, position: Int) {
        val card = getItem(position)
        holder.bind(
            card = card,
            layoutMode = layoutMode,
            onOpen = onOpen,
            onExport = onExport,
            onDelete = onDelete,
            onLongPress = onLongPress,
            selectionMode = selectionMode,
            selectable = isSelectable(card),
            selected = card.snapshotId in selectedIds,
            onToggleSelection = { toggleSelection(card) },
        )
    }

    /**
     * 进复用池的卡放掉封面：池子被空闲预建抬到 6~24 张，封面又按列宽 × 比例解码（单列时整屏宽、
     * 最高 2 倍），不放的话每张离屏卡都攥着一张位图，且不受 Glide 内存缓存预算约束。
     * 只在进池时触发，mCachedViews 里的卡保留图，回滑不闪。
     */
    override fun onViewRecycled(holder: SnapshotViewHolder) {
        super.onViewRecycled(holder)
        holder.clearCover()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SnapshotCard>() {
            override fun areItemsTheSame(oldItem: SnapshotCard, newItem: SnapshotCard): Boolean =
                oldItem.snapshotId == newItem.snapshotId

            override fun areContentsTheSame(oldItem: SnapshotCard, newItem: SnapshotCard): Boolean =
                oldItem == newItem
        }

        /** 选中态刷新用。只为让 DefaultItemAnimator 复用 ViewHolder，不承载业务含义。 */
        private val PAYLOAD_SELECTION = Any()
    }
}

private class SnapshotViewHolder(
    private val binding: ItemSnapshotBinding,
) : RecyclerView.ViewHolder(binding.root) {

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    /** 回收时清图；destroy 期 endAnimations 也会走到这里，所以不能用 Glide.with(view)。 */
    fun clearCover() {
        binding.cover.clearGlideOnRecycle()
    }

    fun bind(
        card: SnapshotCard,
        layoutMode: IllustListLayout,
        onOpen: (SnapshotCard) -> Unit,
        onExport: (SnapshotCard) -> Unit,
        onDelete: (SnapshotCard) -> Unit,
        onLongPress: (SnapshotCard) -> Unit,
        selectionMode: Boolean,
        selectable: Boolean,
        selected: Boolean,
        onToggleSelection: () -> Unit,
    ) {
        val context = binding.root.context
        binding.cover.setHeightRatio(
            if (layoutMode == IllustListLayout.GRID) 1f else card.coverHeightRatio
        )
        if (card.coverFile != null) {
            Glide.with(binding.cover).load(card.coverFile).into(binding.cover)
        } else {
            Glide.with(binding.cover).clear(binding.cover)
        }
        binding.title.text = card.title ?: context.getString(R.string.snapshot_untitled)
        binding.author.text = listOfNotNull(
            card.authorName,
            "ID ${card.authorId ?: card.illustId}",
        ).joinToString(" · ")
        val time = TIME_FORMAT.format(Date(card.createdAt))
        binding.time.text = context.getString(
            R.string.snapshot_meta_format,
            time,
            Formatter.formatShortFileSize(context, card.totalSize),
        )
        binding.autoTag.isVisible = card.isAuto
        binding.commentTag.isVisible = card.includeComments
        binding.originalTag.isVisible = card.originalCoverage != SnapshotOriginalCoverage.NONE
        binding.originalTag.setText(when (card.originalCoverage) {
            SnapshotOriginalCoverage.ALL -> R.string.snapshot_badge_all_original
            SnapshotOriginalCoverage.SOME -> R.string.snapshot_badge_some_original
            SnapshotOriginalCoverage.NONE -> R.string.snapshot_badge_original
        })

        val isR18 = (card.xRestrict ?: 0) > 0
        val pageCount = card.pageCount ?: 1
        binding.r18Badge.isVisible = isR18
        binding.pSize.isVisible = pageCount > 1
        if (pageCount > 1) {
            binding.pSize.text = String.format(Locale.getDefault(), "%dP", pageCount)
        }
        // 多选勾标固定在左上角；进入多选态时隐藏角标行，避免和勾标叠在一起。
        binding.badgeRow.isVisible = !selectionMode

        HistorySelectBadge.bindSelection(binding.selectCheck, binding.deleteButton, selectionMode, selected)
        // 自动快照仅在批量导出多选态隐匿勾选框；批量删除多选态可勾选并批量删除。
        binding.selectCheck.isVisible = selectionMode && selectable

        if (selectionMode) {
            if (!selectable) {
                binding.root.setOnClickListener(null)
                binding.root.setOnLongClickListener(null)
                binding.deleteButton.setOnClickListener(null)
            } else {
                binding.root.setOnClickListener { onToggleSelection() }
                binding.root.setOnLongClickListener(null)
                binding.deleteButton.setOnClickListener(null)
            }
        } else {
            binding.root.setOnClickListener { onOpen(card) }
            binding.root.setOnLongClickListener {
                if (card.isAuto) onLongPress(card) else onExport(card)
                true
            }
            binding.deleteButton.setOnClickListener { onDelete(card) }
        }
    }
}
