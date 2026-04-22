package ceui.pixiv.ui.novel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.LoadingHolder
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.detail.ArtworksMap

/**
 * Backs [UncategorizedNovelsFragment]. Pages through the author's created
 * novels (`/v1/user/novels`) and emits only those with no `series` field —
 * "independent" standalone novels.
 *
 * The filter lives on the DataSource so pagination + response cache + load
 * more all keep working exactly like the real series page.
 *
 * Multi-select state mirrors [NovelSeriesViewModel] verbatim so the fragment
 * can re-use the same action bar UX without any special-casing.
 */
class UncategorizedNovelsViewModel(private val userId: Long) : HoldersViewModel() {

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
    }

    fun toggleSelection(novelId: Long) {
        val current = _selectedIds.value.orEmpty()
        _selectedIds.value = if (novelId in current) current - novelId else current + novelId
    }

    fun selectAll() {
        _selectedIds.value = allNovelIds().toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun allNovelIds(): List<Long> = _itemHolders.value.orEmpty()
        .filterIsInstance<NovelCardHolder>()
        .map { it.novel.id }

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

    // Drives all data loading. We use a plain DataSource with a filter so we
    // get pagination + disk cache for free, then pipe holders into
    // HoldersViewModel's `_itemHolders` via an overridden updateHolders.
    private val _dataSource = object : DataSource<Novel, NovelResponse>(
        dataFetcher = { Client.appApi.getUserCreatedNovels(userId) },
        responseStore = createResponseStore({ "user-$userId-uncategorized-novels" }),
        itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
        filter = { novel ->
            // Pixiv marks content hidden by returning visible=false. Also
            // exclude novels that belong to any series (these already show
            // up under the real series cards on the profile page).
            novel.visible != false && isUncategorized(novel)
        }
    ) {
        override fun updateHolders(holders: List<ListItemHolder>) {
            val filtered = (_itemHolders.value ?: listOf())
                .filterNot { it is LoadingHolder }
                .toMutableList()
            filtered.addAll(holders)
            _itemHolders.value = filtered
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
        // Reset on refresh — the DataSource's updateHolders appends to
        // whatever is already there, so starting from empty avoids dupes.
        _itemHolders.value = emptyList()
        _dataSource.refreshImpl(hint)
    }

    override suspend fun loadMoreImpl() {
        super.loadMoreImpl()
        _dataSource.loadMoreImpl()
    }

    override fun prepareIdMap(fragmentUniqueId: String) {
        val idList = _itemHolders.value.orEmpty()
            .filterIsInstance<NovelCardHolder>()
            .map { it.novel.id }
        ArtworksMap.store[fragmentUniqueId] = idList
    }

    companion object {
        /**
         * "Uncategorized" means no series attached. Pixiv's API reliably
         * omits `series` for standalone novels, but a handful of legacy
         * responses carry `{"id": 0}` — treat both as standalone.
         */
        fun isUncategorized(novel: Novel): Boolean {
            val s = novel.series ?: return true
            return s.id == 0L
        }
    }
}
