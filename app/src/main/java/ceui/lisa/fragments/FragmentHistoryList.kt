package ceui.lisa.fragments

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.helper.StaggeredManager
import ceui.loxia.Illust
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.SpacesItemDecoration
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 浏览历史「插画/漫画」「小说」tab（feeds 框架版）。
 *
 * 列表数据、刷新/翻页/空态全部交给 [FeedFragment] + [HistoryFeedSource]；搜索由宿主
 * [FragmentHistoryTabs] 通过 activity-scope [HistorySearchSharedViewModel] 下发，query
 * 变化触发 feed refresh（source 读取当前 query 切数据源）。多选态住在 [HistorySelectionViewModel]，
 * 通过 [updateItems] 回灌到卡片的 isSelectionMode/isSelected（[syncSelection]，自带差异守卫防死循环）。
 * 删除走 [deleteHistoryEntities] + [FeedViewModel.removeItems]，就地摘条不整列重拉。
 */
class FragmentHistoryList : FeedFragment(), SelectableHistoryTab {

    private val historyType: Int by lazy { arguments?.getInt(ARG_TYPE, 0) ?: 0 }
    private val searchVm: HistorySearchSharedViewModel by activityViewModels()
    private val selectionVm: HistorySelectionViewModel by viewModels()

    // 懒加载:三 tab 在同一 ViewPager(BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT),只在 tab 真正
    // 可见(首次 RESUMED)才拉,避免开页就并发三次网络请求。
    override val feedViewModel by feedViewModels(autoLoad = false) {
        HistoryFeedSource(historyType, searchVm)
    }

    /** 时间格式化器:renderer 复用,别每次 onBind 都 new SimpleDateFormat。随 fragment 重建拿到当前 locale。 */
    internal val historyTimeFormat by lazy {
        SimpleDateFormat(getString(R.string.string_350), Locale.getDefault())
    }

    /**
     * 插画历史当前列宽（px），与标准瀑布流 IllustFeedFragment.illustColumnWidthPx 同源：
     * 取 LayoutManager 实时宽度，首帧兜底屏宽，按用户「每行几列」设置分列。
     */
    internal val historyColumnWidthPx: Int
        get() {
            val listWidth = feedBinding.feedListView.layoutManager?.width?.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            return (listWidth / Shaft.sSettings.lineCount).coerceAtLeast(1)
        }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        listOf(historyIllustRenderer(), historyNovelRenderer())

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        // 插画历史跟随用户「每行几列」设置（与全仓插画瀑布流同源，见 IllustFeedFragment）——
        // 曾经这里硬编码成 2，是唯一一条自成一派、无视该设置的插画列表。小说历史是竖向单列卡。
        // 用 StaggeredManager 而不是裸 StaggeredGridLayoutManager：后者存在的理由就是吞掉
        // AOSP predictive-layout 在 fling + 插页同帧时的内部崩溃。
        val spanCount = if (historyType == TYPE_NOVEL) 1 else Shaft.sSettings.lineCount
        return StaggeredManager(spanCount, RecyclerView.VERTICAL)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.itemAnimator = null
        // 插画历史的间距对齐全站其它插画瀑布流(IllustFeedFragment 同款 8dp SpacesItemDecoration:
        // 按 lineCount 分档给左/中/右不同的左右偏移,边缘 8dp、中缝 8dp)。原先是靠 cell_history_illust_v3
        // 自带的 5dp layout_margin 撑间距 —— 比全站标准窄一圈、几乎贴着屏幕边,且不跟 lineCount 走。
        // 小说历史是单列:SpacesItemDecoration 按 spanIndex 算左右偏移,单列会算出左 8dp/右 4dp 的
        // 偏心,故不挂,继续用 cell_history_novel_v3 自带的 margin。
        if (historyType != TYPE_NOVEL) {
            listView.addItemDecoration(SpacesItemDecoration(DensityUtil.dp2px(8.0f)))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 选中态变化 → 回灌卡片
        selectionVm.selectionMode.observe(viewLifecycleOwner) { syncSelection() }
        selectionVm.selectedIds.observe(viewLifecycleOwner) { syncSelection() }
        // 追页后新卡以「非多选」态入列，跟随当前多选态回灌
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { syncSelection() }
            }
        }
        // host toolbar 的 SearchView 输入通过 activity-scope SharedVM 下发；query 变化重刷。
        //
        // 按「与上次真正拉过的 query 是否相同」判重，而不是 drop(1) 跳过首发：query 归 activity
        // 作用域，本 tab 的视图销毁期间（三 tab pager，滑远了会销毁）它照样会变，等本 tab 重建时
        // drop(1) 恰好把这次变化当成粘性首发丢掉，而 ensureLoaded 又因 hasLoadedOnce 已置位不再
        // 拉——列表内容就与搜索框里的词对不上了。记录已应用值则不论中间隔了几次视图重建都成立。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchVm.query.collect { query ->
                    // 还没出过首屏时不插手：首屏归 ensureLoaded（autoLoad=false 的懒加载约定），
                    // 它自会按当时的 query 拉。
                    if (!feedViewModel.uiState.value.hasLoadedOnce) return@collect
                    val normalized = query?.trim().orEmpty().ifEmpty { null }
                    if (searchVm.isQueryApplied(historyType, normalized)) return@collect
                    feedViewModel.refresh()
                }
            }
        }
        // 旋转等 view 重建时选择态可能还留着，但 host toolbar 已回普通态 → 复位。
        selectionVm.setSelectionMode(false)
    }

    // ── 多选态回灌 feed（差异守卫防止 uiState.collect ↔ updateItems 死循环）─────────────
    private fun syncSelection() {
        if (view == null) return
        val mode = selectionVm.selectionMode.value == true
        val selected = selectionVm.selectedIds.value.orEmpty()
        val needsUpdate = feedViewModel.uiState.value.items.any { item ->
            when (item) {
                is HistoryIllustFeedItem ->
                    item.isSelectionMode != mode || item.isSelected != (item.entity.illustID.toLong() in selected)
                is HistoryNovelFeedItem ->
                    item.isSelectionMode != mode || item.isSelected != (item.entity.illustID.toLong() in selected)
                else -> false
            }
        }
        if (!needsUpdate) return
        feedViewModel.updateItems<HistoryIllustFeedItem> {
            it.copy(isSelectionMode = mode, isSelected = it.entity.illustID.toLong() in selected)
        }
        feedViewModel.updateItems<HistoryNovelFeedItem> {
            it.copy(isSelectionMode = mode, isSelected = it.entity.illustID.toLong() in selected)
        }
    }

    // ── renderer 回调（HistoryFeed.kt 里的扩展 renderer 调用）────────────────────────
    internal fun toggleHistorySelect(entity: IllustHistoryEntity) = selectionVm.toggle(entity.illustID.toLong())

    internal fun openHistoryUser(uid: Long) {
        startActivity(Intent(requireContext(), UActivity::class.java).apply {
            putExtra(Params.USER_ID, uid)
        })
    }

    internal fun openHistoryIllust(illust: Illust) {
        val all = loadedIllusts()
        if (all.isEmpty()) return
        val pageData = PageData(all)
        Container.get().addPageToMap(pageData)
        val index = all.indexOfFirst { it.id == illust.id }.coerceAtLeast(0)
        startActivity(Intent(requireContext(), VActivity::class.java).apply {
            putExtra(Params.POSITION, index)
            putExtra(Params.PAGE_UUID, pageData.uuid)
        })
    }

    internal fun confirmDeleteHistory(entity: IllustHistoryEntity) {
        val act = activity ?: return
        WitDialog.MessageDialogBuilder(act)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_352)
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                deleteHistory(listOf(entity))
            }
            .show()
    }

    private fun deleteHistory(entities: List<IllustHistoryEntity>, onComplete: (Int) -> Unit = {}) {
        if (entities.isEmpty()) { onComplete(0); return }
        val ids = entities.map { it.illustID }.toSet()
        viewLifecycleOwner.lifecycleScope.launch {
            // 删除本体不随 view 生死：点完删除立刻退出页面会取消本协程，把逐条删除拦腰斩断，
            // 剩余条目本地/远端都没删、下次进来「复活」。NonCancellable 保证这一批删完；
            // 之后的列表更新才归 view 管，view 没了协程在此结束即可。
            withContext(NonCancellable) { deleteHistoryEntities(historyType, entities) }
            if (view == null) return@launch
            feedViewModel.removeItems { item ->
                (item as? HistoryIllustFeedItem)?.entity?.illustID in ids ||
                    (item as? HistoryNovelFeedItem)?.entity?.illustID in ids
            }
            onComplete(ids.size)
        }
    }

    // ── 当前已加载数据快照 ──────────────────────────────────────────────────────
    private fun loadedEntities(): List<IllustHistoryEntity> =
        feedViewModel.uiState.value.items.mapNotNull { item ->
            when (item) {
                is HistoryIllustFeedItem -> item.entity
                is HistoryNovelFeedItem -> item.entity
                else -> null
            }
        }

    private fun loadedIllusts(): List<Illust> =
        feedViewModel.uiState.value.items.filterIsInstance<HistoryIllustFeedItem>().map { it.illust }

    /** host 一键清空全部历史 (#886) 后调一下，让本 tab 重新拉数据源。 */
    fun reloadFromDao() {
        if (view == null) return
        feedViewModel.refresh()
    }

    // ── SelectableHistoryTab：多选删除，具体状态在 [selectionVm] ──────────────────
    override val selectedCount: LiveData<Int> get() = selectionVm.selectedCount
    override fun hasItems(): Boolean = loadedEntities().isNotEmpty()
    override fun isAllSelected(): Boolean {
        val ids = loadedEntities().map { it.illustID.toLong() }
        return ids.isNotEmpty() && selectionVm.selectedIds.value.orEmpty().containsAll(ids)
    }
    override fun enterSelectionMode() = selectionVm.setSelectionMode(true)
    override fun exitSelectionMode() = selectionVm.setSelectionMode(false)
    override fun toggleSelectAll() {
        val ids = loadedEntities().map { it.illustID.toLong() }
        if (ids.isNotEmpty() && selectionVm.selectedIds.value.orEmpty().containsAll(ids)) {
            selectionVm.clear()
        } else {
            selectionVm.setSelected(ids.toSet())
        }
    }
    override fun deleteSelected(onComplete: (Int) -> Unit) {
        val selected = selectionVm.selectedIds.value.orEmpty()
        val targets = loadedEntities().filter { it.illustID.toLong() in selected }
        deleteHistory(targets) { deleted ->
            selectionVm.setSelectionMode(false)
            onComplete(deleted)
        }
    }

    companion object {
        private const val ARG_TYPE = "history_type"
        private const val TYPE_NOVEL = 1

        fun newInstance(type: Int): FragmentHistoryList = FragmentHistoryList().apply {
            arguments = Bundle().apply { putInt(ARG_TYPE, type) }
        }
    }
}
