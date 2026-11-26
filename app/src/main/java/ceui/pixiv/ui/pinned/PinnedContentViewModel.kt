package ceui.pixiv.ui.pinned

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.SearchEntity
import ceui.lisa.utils.PixivOperate
import ceui.pixiv.db.EntityWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 「我置顶的内容」三页共享 ViewModel（归宿主 [PinnedTabsFragment] 持有）。
 *
 * 统一掌管：
 * 1. 置顶标签（`search_table.pinned = 1`）、置顶作者（`general_table.PINNED_USER`）、
 *    全量搜索历史（`search_table.pinned = 0`，不设 LIMIT）三份本地数据及其预归一检索索引；
 * 2. 多语言实时搜索关键词（中 / 日 / 英，[PinnedSearchMatcher]）与原子化过滤结果；
 * 3. 与列表严格同源的三 Tab 数量统计（总条数 + 搜索命中数），原子计算杜绝计数短暂错位；
 * 4. 串行化的 DB 读/写与跨 Tab 写操作（防并发旧刷新覆写新删改）。
 */
internal class PinnedContentViewModel : ViewModel() {

    private data class IndexedEntry<T>(
        val item: T,
        val searchIndex: List<String>,
    )

    private data class FilteredTabState<T>(
        val items: List<T> = emptyList(),
        val total: Int = 0,
        val isFiltering: Boolean = false,
    )

    data class TabCount(val matched: Int, val total: Int)

    data class TabCounts(
        val tags: TabCount = TabCount(0, 0),
        val users: TabCount = TabCount(0, 0),
        val history: TabCount = TabCount(0, 0),
        val isFiltering: Boolean = false,
        val hasLoaded: Boolean = false,
    ) {
        fun forPosition(position: Int): TabCount = when (position) {
            TAB_TAGS -> tags
            TAB_USERS -> users
            TAB_HISTORY -> history
            else -> TabCount(0, 0)
        }
    }

    data class ExportSnapshot(
        val tags: List<PinnedTagExportRow>,
        val users: List<PinnedUserExportRow>,
        val history: List<SearchHistoryExportRow>,
    ) {
        fun countOf(category: PinnedExportCategory): Int = when (category) {
            PinnedExportCategory.TAGS -> tags.size
            PinnedExportCategory.USERS -> users.size
            PinnedExportCategory.HISTORY -> history.size
        }

        fun totalOf(categories: Set<PinnedExportCategory>): Int =
            categories.sumOf(::countOf)
    }

    private val dbMutex = Mutex()
    private var refreshJob: Job? = null

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val queryTokens: StateFlow<List<String>> = _searchQuery
        .map { PinnedSearchMatcher.tokenize(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _allTags = MutableStateFlow<List<IndexedEntry<PinnedTagItemHolder>>>(emptyList())
    private val _allUsers = MutableStateFlow<List<IndexedEntry<PinnedUserItemHolder>>>(emptyList())
    private val _allHistory = MutableStateFlow<List<IndexedEntry<SearchHistoryItemHolder>>>(emptyList())

    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val tagsState: StateFlow<FilteredTabState<PinnedTagItemHolder>> =
        combine(_allTags, queryTokens) { entries, tokens ->
            filterEntries(entries, tokens)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, FilteredTabState())

    private val usersState: StateFlow<FilteredTabState<PinnedUserItemHolder>> =
        combine(_allUsers, queryTokens) { entries, tokens ->
            filterEntries(entries, tokens)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, FilteredTabState())

    private val historyState: StateFlow<FilteredTabState<SearchHistoryItemHolder>> =
        combine(_allHistory, queryTokens) { entries, tokens ->
            filterEntries(entries, tokens)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, FilteredTabState())

    val filteredTags: StateFlow<List<PinnedTagItemHolder>> = tagsState
        .map { it.items }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredUsers: StateFlow<List<PinnedUserItemHolder>> = usersState
        .map { it.items }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredHistory: StateFlow<List<SearchHistoryItemHolder>> = historyState
        .map { it.items }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 每页的 `(matched, total)` 均来自同一份原子 [FilteredTabState]，
     * 绝不会因 `_all*`（主线程）与 `filtered*`（Default 线程）异步脱节而闪出 `matched > total`。
     */
    val tabCounts: StateFlow<TabCounts> = combine(
        tagsState,
        usersState,
        historyState,
        _hasLoaded,
    ) { tags, users, history, loaded ->
        TabCounts(
            tags = TabCount(tags.items.size, tags.total),
            users = TabCount(users.items.size, users.total),
            history = TabCount(history.items.size, history.total),
            isFiltering = tags.isFiltering || users.isFiltering || history.isFiltering,
            hasLoaded = loaded,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TabCounts())

    fun setSearchQuery(query: String) {
        if (_searchQuery.value != query) {
            _searchQuery.value = query
        }
    }

    fun clearSearchQuery() {
        setSearchQuery("")
    }

    fun isFiltering(): Boolean = queryTokens.value.isNotEmpty()

    fun hasItemsInTab(position: Int): Boolean = when (position) {
        TAB_TAGS -> _allTags.value.isNotEmpty()
        TAB_USERS -> _allUsers.value.isNotEmpty()
        TAB_HISTORY -> _allHistory.value.isNotEmpty()
        else -> false
    }

    fun exportSnapshot(filteredOnly: Boolean): ExportSnapshot {
        val tags = (if (filteredOnly) tagsState.value.items else _allTags.value.map { it.item }).map { item ->
            PinnedTagExportRow(
                keyword = item.entity.keyword.orEmpty(),
                translatedName = item.tag.translated_name,
                isCombo = item.isCombo,
                searchTimeMillis = item.entity.searchTime,
            )
        }
        val users = (if (filteredOnly) usersState.value.items else _allUsers.value.map { it.item }).map { item ->
            PinnedUserExportRow(
                id = item.user.id,
                name = item.user.name.orEmpty(),
                account = item.user.account,
            )
        }
        val history = (if (filteredOnly) historyState.value.items else _allHistory.value.map { it.item }).map { item ->
            SearchHistoryExportRow(
                keyword = item.keyword,
                searchType = item.searchType,
                searchTimeMillis = item.searchTime,
            )
        }
        return ExportSnapshot(tags = tags, users = users, history = history)
    }

    fun refreshAll(showSpinner: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            try {
                dbMutex.withLock {
                    val (tags, users, history) = withContext(Dispatchers.IO) {
                        coroutineScope {
                            val tagsJob = async { loadPinnedTags() }
                            val usersJob = async { loadPinnedUsers() }
                            val historyJob = async { loadAllSearchHistory() }
                            Triple(tagsJob.await(), usersJob.await(), historyJob.await())
                        }
                    }
                    _allTags.value = tags
                    _allUsers.value = users
                    _allHistory.value = history
                    _hasLoaded.value = true
                }
            } finally {
                if (showSpinner) _refreshing.value = false
            }
        }
    }

    fun deletePinnedTag(entity: SearchEntity) {
        runMutation {
            val tags = withContext(Dispatchers.IO) {
                searchDao().deleteSearchEntity(entity)
                loadPinnedTags()
            }
            _allTags.value = tags
        }
    }

    fun clearPinnedTags() {
        runMutation {
            withContext(Dispatchers.IO) {
                searchDao().deleteAllPinned()
            }
            _allTags.value = emptyList()
        }
    }

    fun deletePinnedUser(entityWrapper: EntityWrapper, appContext: Context, userId: Long) {
        runMutation {
            entityWrapper.deletePinnedUser(appContext, userId)
            val users = withContext(Dispatchers.IO) { loadPinnedUsers() }
            _allUsers.value = users
        }
    }

    fun clearPinnedUsers(entityWrapper: EntityWrapper, appContext: Context) {
        runMutation {
            entityWrapper.clearPinnedUsers(appContext)
            _allUsers.value = emptyList()
        }
    }

    fun pinHistoryItem(entity: SearchEntity) {
        val keyword = entity.keyword?.takeIf { it.isNotBlank() } ?: return
        runMutation {
            val (tags, history) = withContext(Dispatchers.IO) {
                PixivOperate.insertPinnedSearchHistory(keyword, entity.searchType, true)
                loadPinnedTags() to loadAllSearchHistory()
            }
            _allTags.value = tags
            _allHistory.value = history
        }
    }

    fun deleteHistoryItem(entity: SearchEntity) {
        runMutation {
            val history = withContext(Dispatchers.IO) {
                searchDao().deleteSearchEntity(entity)
                loadAllSearchHistory()
            }
            _allHistory.value = history
        }
    }

    fun clearSearchHistory() {
        runMutation {
            withContext(Dispatchers.IO) {
                searchDao().deleteAllUnpinned()
            }
            _allHistory.value = emptyList()
        }
    }

    /** 写操作取消在途旧刷新并通过互斥锁串行落库，保证旧快照不会回写覆盖新删改。 */
    private fun runMutation(block: suspend () -> Unit) {
        refreshJob?.cancel()
        viewModelScope.launch {
            dbMutex.withLock {
                block()
            }
        }
    }

    private fun <T> filterEntries(
        entries: List<IndexedEntry<T>>,
        tokens: List<String>,
    ): FilteredTabState<T> {
        val items = if (tokens.isEmpty()) {
            entries.map { it.item }
        } else {
            entries.mapNotNull { entry ->
                if (PinnedSearchMatcher.matchesIndex(tokens, entry.searchIndex)) entry.item else null
            }
        }
        return FilteredTabState(
            items = items,
            total = entries.size,
            isFiltering = tokens.isNotEmpty(),
        )
    }

    private fun loadPinnedTags(): List<IndexedEntry<PinnedTagItemHolder>> =
        searchDao().getAllPinned()
            .filterNot { it.keyword.isNullOrBlank() }
            .map { entity ->
                val item = PinnedTagItemHolder(entity)
                IndexedEntry(
                    item = item,
                    searchIndex = PinnedSearchMatcher.buildIndex(
                        item.entity.keyword,
                        item.tag.name,
                        item.tag.translated_name,
                        item.displayName,
                    ),
                )
            }

    private fun loadPinnedUsers(): List<IndexedEntry<PinnedUserItemHolder>> =
        PinnedUsers.loadAll(Shaft.getContext())
            .map { user ->
                val item = PinnedUserItemHolder(user)
                IndexedEntry(
                    item = item,
                    searchIndex = PinnedSearchMatcher.buildIndex(
                        item.user.name,
                        item.user.account,
                        item.user.id.toString(),
                    ),
                )
            }

    private fun loadAllSearchHistory(): List<IndexedEntry<SearchHistoryItemHolder>> =
        searchDao().getAllUnpinned()
            .filterNot { it.keyword.isNullOrBlank() }
            .map { entity ->
                val item = SearchHistoryItemHolder(entity)
                IndexedEntry(
                    item = item,
                    searchIndex = PinnedSearchMatcher.buildIndex(
                        item.keyword,
                        item.translatedName,
                    ),
                )
            }

    companion object {
        const val TAB_TAGS = 0
        const val TAB_USERS = 1
        const val TAB_HISTORY = 2
        const val TAB_COUNT = 3
    }
}

private fun searchDao() = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao()
