package ceui.lisa.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.pixiv.db.GeneralEntity
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
 * 浏览历史「用户」tab（feeds 框架版）。结构同 [FragmentHistoryList]，但数据是 general_table 的
 * 用户浏览记录（[HistoryUserFeedSource]），单一条目类型、竖排线性、无搜索（对齐旧 HistoryUserViewModel）。
 * 多选态住在 [HistorySelectionViewModel]（键 = entity.id/uid），通过 [updateItems] 回灌卡片选中态；
 * 删除走 [deleteUserHistoryEntities] + [FeedViewModel.removeItems]。
 */
class FragmentHistoryUserList : FeedFragment(), SelectableHistoryTab {

    private val selectionVm: HistorySelectionViewModel by viewModels()

    // 懒加载:同 FragmentHistoryList,ViewPager tab 只在真正可见时才拉。
    override val feedViewModel by feedViewModels(autoLoad = false) { HistoryUserFeedSource() }

    /** 时间格式化器:renderer 复用,别每次 onBind 都 new SimpleDateFormat。 */
    internal val userTimeFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        listOf(historyUserRenderer())

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager =
        LinearLayoutManager(requireContext())

    override fun onListReady(listView: RecyclerView) {
        listView.itemAnimator = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectionVm.selectionMode.observe(viewLifecycleOwner) { syncSelection() }
        selectionVm.selectedIds.observe(viewLifecycleOwner) { syncSelection() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { syncSelection() }
            }
        }
        // 同 FragmentHistoryList：view 重建复位选择态，避免残留勾选框退不出去。
        selectionVm.setSelectionMode(false)
    }

    // ── 多选态回灌 feed（差异守卫防止 uiState.collect ↔ updateItems 死循环）─────────────
    private fun syncSelection() {
        if (view == null) return
        val mode = selectionVm.selectionMode.value == true
        val selected = selectionVm.selectedIds.value.orEmpty()
        val needsUpdate = feedViewModel.uiState.value.items.any { item ->
            item is HistoryUserFeedItem &&
                (item.isSelectionMode != mode || item.isSelected != (item.entity.id in selected))
        }
        if (!needsUpdate) return
        feedViewModel.updateItems<HistoryUserFeedItem> {
            it.copy(isSelectionMode = mode, isSelected = it.entity.id in selected)
        }
    }

    // ── renderer 回调（HistoryUserFeed.kt 里的扩展 renderer 调用）────────────────────
    internal fun toggleUserHistorySelect(entity: GeneralEntity) = selectionVm.toggle(entity.id)

    internal fun confirmDeleteUserHistory(entity: GeneralEntity) {
        val act = activity ?: return
        WitDialog.MessageDialogBuilder(act)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_352)
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                deleteUsers(listOf(entity))
            }
            .show()
    }

    private fun deleteUsers(entities: List<GeneralEntity>, onComplete: (Int) -> Unit = {}) {
        if (entities.isEmpty()) { onComplete(0); return }
        val ids = entities.map { it.id }.toSet()
        viewLifecycleOwner.lifecycleScope.launch {
            // 同 FragmentHistoryList.deleteHistory：删除本体不随 view 生死，防半途取消「复活」。
            withContext(NonCancellable) { deleteUserHistoryEntities(entities) }
            if (view == null) return@launch
            feedViewModel.removeItems { (it as? HistoryUserFeedItem)?.entity?.id in ids }
            onComplete(ids.size)
        }
    }

    private fun loadedEntities(): List<GeneralEntity> =
        feedViewModel.uiState.value.items.filterIsInstance<HistoryUserFeedItem>().map { it.entity }

    /** host 一键清空全部历史 (#886) 后调一下，让本 tab 重新拉数据源。 */
    fun reloadFromDao() {
        if (view == null) return
        feedViewModel.refresh()
    }

    // ── SelectableHistoryTab ────────────────────────────────────────────────────
    override val selectedCount: LiveData<Int> get() = selectionVm.selectedCount
    override fun hasItems(): Boolean = loadedEntities().isNotEmpty()
    override fun isAllSelected(): Boolean {
        val ids = loadedEntities().map { it.id }
        return ids.isNotEmpty() && selectionVm.selectedIds.value.orEmpty().containsAll(ids)
    }
    override fun enterSelectionMode() = selectionVm.setSelectionMode(true)
    override fun exitSelectionMode() = selectionVm.setSelectionMode(false)
    override fun toggleSelectAll() {
        val ids = loadedEntities().map { it.id }
        if (ids.isNotEmpty() && selectionVm.selectedIds.value.orEmpty().containsAll(ids)) {
            selectionVm.clear()
        } else {
            selectionVm.setSelected(ids.toSet())
        }
    }
    override fun deleteSelected(onComplete: (Int) -> Unit) {
        val selected = selectionVm.selectedIds.value.orEmpty()
        val targets = loadedEntities().filter { it.id in selected }
        deleteUsers(targets) { deleted ->
            selectionVm.setSelectionMode(false)
            onComplete(deleted)
        }
    }
}
