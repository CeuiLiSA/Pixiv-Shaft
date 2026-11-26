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
import kotlinx.coroutines.withContext

/**
 * 「我置顶的内容」三页共享 ViewModel（归宿主 [PinnedTabsFragment] 持有）。
 *
 * 统一掌管：
 * 1. 置顶标签（`search_table.pinned = 1`）、置顶作者（`general_table.PINNED_USER`）、
 *    全量搜索历史（`search_table.pinned = 0`，不设 LIMIT）三份本地数据；
 * 2. 多语言实时搜索关键词（中 / 日 / 英，[PinnedSearchMatcher]）与三页过滤结果；
 * 3. 与列表严格同源的三 Tab 数量统计（总条数 + 搜索命中数），杜绝计数与列表脱节；
 * 4. 跨 Tab 写操作（如在「搜索历史」一键置顶后同步刷新「标签」与「搜索历史」两页及计数）。
 */
internal class PinnedContentViewModel : ViewModel() {

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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val queryTokens: StateFlow<List<String>> = _searchQuery
        .map { PinnedSearchMatcher.tokenize(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _allTags = MutableStateFlow<List<PinnedTagItemHolder>>(emptyList())
    private val _allUsers = MutableStateFlow<List<PinnedUserItemHolder>>(emptyList())
    private val _allHistory = MutableStateFlow<List<SearchHistoryItemHolder>>(emptyList())

    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val filteredTags: StateFlow<List<PinnedTagItemHolder>> = combine(_allTags, queryTokens) { list, tokens ->
        if (tokens.isEmpty()) list
        else list.filter { item ->
            PinnedSearchMatcher.matches(
                tokens,
                item.entity.keyword,
                item.tag.name,
                item.tag.translated_name,
                item.displayName,
            )
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredUsers: StateFlow<List<PinnedUserItemHolder>> = combine(_allUsers, queryTokens) { list, tokens ->
        if (tokens.isEmpty()) list
        else list.filter { item ->
            PinnedSearchMatcher.matches(
                tokens,
                item.user.name,
                item.user.account,
                item.user.id.toString(),
            )
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredHistory: StateFlow<List<SearchHistoryItemHolder>> = combine(_allHistory, queryTokens) { list, tokens ->
        if (tokens.isEmpty()) list
        else list.filter { item ->
            PinnedSearchMatcher.matches(
                tokens,
                item.keyword,
                item.translatedName,
            )
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tabCounts: StateFlow<TabCounts> = combine(
        combine(_allTags, filteredTags) { all, filtered -> TabCount(filtered.size, all.size) },
        combine(_allUsers, filteredUsers) { all, filtered -> TabCount(filtered.size, all.size) },
        combine(_allHistory, filteredHistory) { all, filtered -> TabCount(filtered.size, all.size) },
        queryTokens,
        _hasLoaded,
    ) { tags, users, history, tokens, loaded ->
        TabCounts(
            tags = tags,
            users = users,
            history = history,
            isFiltering = tokens.isNotEmpty(),
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
        val tags = (if (filteredOnly) filteredTags.value else _allTags.value).map { item ->
            PinnedTagExportRow(
                keyword = item.entity.keyword.orEmpty(),
                translatedName = item.tag.translated_name,
                isCombo = item.isCombo,
                searchTimeMillis = item.entity.searchTime,
            )
        }
        val users = (if (filteredOnly) filteredUsers.value else _allUsers.value).map { item ->
            PinnedUserExportRow(
                id = item.user.id,
                name = item.user.name.orEmpty(),
                account = item.user.account,
            )
        }
        val history = (if (filteredOnly) filteredHistory.value else _allHistory.value).map { item ->
            SearchHistoryExportRow(
                keyword = item.keyword,
                searchType = item.searchType,
                searchTimeMillis = item.searchTime,
            )
        }
        return ExportSnapshot(tags = tags, users = users, history = history)
    }

    fun refreshAll(showSpinner: Boolean = false) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            try {
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
            } finally {
                if (showSpinner) _refreshing.value = false
            }
        }
    }

    fun deletePinnedTag(entity: SearchEntity) {
        viewModelScope.launch {
            val tags = withContext(Dispatchers.IO) {
                searchDao().deleteSearchEntity(entity)
                loadPinnedTags()
            }
            _allTags.value = tags
        }
    }

    fun clearPinnedTags() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                searchDao().deleteAllPinned()
            }
            _allTags.value = emptyList()
        }
    }

    fun deletePinnedUser(entityWrapper: EntityWrapper, appContext: Context, userId: Long) {
        viewModelScope.launch {
            entityWrapper.deletePinnedUser(appContext, userId)
            val users = withContext(Dispatchers.IO) { loadPinnedUsers() }
            _allUsers.value = users
        }
    }

    fun clearPinnedUsers(entityWrapper: EntityWrapper, appContext: Context) {
        viewModelScope.launch {
            entityWrapper.clearPinnedUsers(appContext)
            _allUsers.value = emptyList()
        }
    }

    fun pinHistoryItem(entity: SearchEntity) {
        val keyword = entity.keyword?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val (tags, history) = withContext(Dispatchers.IO) {
                PixivOperate.insertPinnedSearchHistory(keyword, entity.searchType, true)
                loadPinnedTags() to loadAllSearchHistory()
            }
            _allTags.value = tags
            _allHistory.value = history
        }
    }

    fun deleteHistoryItem(entity: SearchEntity) {
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) {
                searchDao().deleteSearchEntity(entity)
                loadAllSearchHistory()
            }
            _allHistory.value = history
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                searchDao().deleteAllUnpinned()
            }
            _allHistory.value = emptyList()
        }
    }

    companion object {
        const val TAB_TAGS = 0
        const val TAB_USERS = 1
        const val TAB_HISTORY = 2
        const val TAB_COUNT = 3
    }
}

private fun searchDao() = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao()

private fun loadPinnedTags(): List<PinnedTagItemHolder> =
    searchDao().getAllPinned()
        .filterNot { it.keyword.isNullOrBlank() }
        .map { PinnedTagItemHolder(it) }

private fun loadPinnedUsers(): List<PinnedUserItemHolder> =
    PinnedUsers.loadAll(Shaft.getContext())
        .map { PinnedUserItemHolder(it) }

private fun loadAllSearchHistory(): List<SearchHistoryItemHolder> =
    searchDao().getAllUnpinned()
        .filterNot { it.keyword.isNullOrBlank() }
        .map { SearchHistoryItemHolder(it) }
