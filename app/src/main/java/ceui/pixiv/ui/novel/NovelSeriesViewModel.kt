package ceui.pixiv.ui.novel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesResp
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.LoadMoreOwner
import ceui.pixiv.ui.common.LoadingHolder
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.detail.ArtworksMap
import ceui.pixiv.ui.detail.UserInfoHolder
import kotlinx.coroutines.launch
import timber.log.Timber

class NovelSeriesViewModel(
    private val seriesId: Long,
) : HoldersViewModel() {

    private var _lastOrder: Int? = null

    private val _series = MutableLiveData<NovelSeriesResp>()
    val series: LiveData<NovelSeriesResp> = _series

    // ── Multi-select state ──────────────────────────────────────────
    // Kept in the VM so it survives config changes (rotation, theme
    // switch). The fragment observes both values to drive its top-right
    // toggle icon and the bottom action bar.
    private val _isMultiSelect = MutableLiveData(false)
    val isMultiSelect: LiveData<Boolean> = _isMultiSelect

    private val _selectedIds = MutableLiveData<Set<Long>>(emptySet())
    val selectedIds: LiveData<Set<Long>> = _selectedIds

    fun setMultiSelectMode(enabled: Boolean) {
        if (_isMultiSelect.value == enabled) return
        _isMultiSelect.value = enabled
        if (!enabled) {
            _selectedIds.value = emptySet()
        }
        updateHoldersSelectionState()
    }

    fun toggleSelection(novelId: Long) {
        val current = _selectedIds.value.orEmpty()
        _selectedIds.value = if (novelId in current) current - novelId else current + novelId
        updateHoldersSelectionState()
    }

    fun selectAll() {
        _selectedIds.value = allNovelIds().toSet()
        updateHoldersSelectionState()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        updateHoldersSelectionState()
    }

    private fun updateHoldersSelectionState() {
        val multiSelect = _isMultiSelect.value == true
        val selected = _selectedIds.value.orEmpty()
        val currentList = _itemHolders.value ?: return
        _itemHolders.value = currentList.map { holder ->
            if (holder is NovelCardHolder) {
                val sel = holder.novel.id in selected
                if (holder.isMultiSelectMode != multiSelect || holder.isSelected != sel) {
                    NovelCardHolder(holder.novel).also {
                        it.isMultiSelectMode = multiSelect
                        it.isSelected = sel
                    }
                } else {
                    holder
                }
            } else {
                holder
            }
        }
    }

    fun allNovelIds(): List<Long> = _itemHolders.value.orEmpty()
        .filterIsInstance<NovelCardHolder>()
        .map { it.novel.id }

    /** 当前已加载到列表里的全部章节（按出现顺序）。「合并下载」用来预填充，之后
     *  任务本身还会继续翻页补齐。 */
    fun allLoadedNovels(): List<Novel> = _itemHolders.value.orEmpty()
        .filterIsInstance<NovelCardHolder>()
        .map { it.novel }

    fun selectedNovels(): List<Novel> {
        val selected = _selectedIds.value.orEmpty()
        if (selected.isEmpty()) return emptyList()
        return _itemHolders.value.orEmpty()
            .filterIsInstance<NovelCardHolder>()
            .filter { it.novel.id in selected }
            .map { it.novel }
    }

    private val _seriesNovelsDataSource = object : DataSource<Novel, NovelSeriesResp>(
        dataFetcher = { Client.appApi.getNovelSeries(seriesId, _lastOrder) },
        responseStore = createResponseStore({ "novel-series-$seriesId" }),
        itemMapper = { novel -> listOf(NovelCardHolder(novel)) }
    ) {
        override fun updateHolders(holders: List<ListItemHolder>) {
            // 从现有列表中剔除 LoadingHolder
            val filteredList =
                (_itemHolders.value ?: listOf()).filterNot { it is LoadingHolder }.toMutableList()
            Timber.d("dfsasfs2 ${holders.size}")
            // 添加新数据
            filteredList.addAll(holders)

            // 更新列表
            _itemHolders.value = filteredList
            _refreshState.value = RefreshState.LOADED(
                hasContent = true,
                hasNext = hasNext()
            )
        }
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        val context = Shaft.getContext()
        val resp = Client.appApi.getNovelSeries(seriesId)
        _series.value = resp
        val result = mutableListOf<ListItemHolder>()
        resp.novel_series_detail?.let {
            result.add(NovelSeriesHeaderHolder(it))
        }
        result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
        result.add(UserInfoHolder(resp.novel_series_detail?.user?.id ?: 0L))
        result.add(RedSectionHeaderHolder(
            context.getString(
                R.string.total_works_count,
                resp.novel_series_detail?.content_count
            )))
        result.addAll(resp.displayList.map { novel -> NovelCardHolder(novel) })
        _lastOrder = resp.novels?.size
        _itemHolders.value = result
        val hasNext = resp.next_url != null
        _refreshState.value = RefreshState.LOADED(
            hasContent = true, hasNext = hasNext
        )
        if (hasNext) {
            _seriesNovelsDataSource.refreshImpl(hint)
        }
    }

    override suspend fun loadMoreImpl() {
        super.loadMoreImpl()
        _seriesNovelsDataSource.loadMoreImpl()
    }

    override fun prepareIdMap(fragmentUniqueId: String) {
        val filteredList = _itemHolders.value.orEmpty()
            .filterIsInstance<NovelCardHolder>()
            .map { it.novel.id }

        ArtworksMap.store[fragmentUniqueId] = filteredList
    }
}