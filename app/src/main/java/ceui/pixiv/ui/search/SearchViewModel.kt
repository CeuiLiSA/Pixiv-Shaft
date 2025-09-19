package ceui.pixiv.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import ceui.loxia.Client
import ceui.loxia.Event
import ceui.loxia.ObjectType
import ceui.loxia.SearchSuggestionResponse
import ceui.loxia.Tag
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class SearchViewModel(showSuggestion: Boolean, initialKeyword: String) : ViewModel() {


    val tagList = MutableLiveData<List<Tag>>()

    val illustSelectedRadioTabIndex = MutableLiveData(0)
    val novelSelectedRadioTabIndex = MutableLiveData(0)

    val inputDraft = MutableLiveData("")

    private val _searchSuggestion = MutableLiveData<SearchSuggestionResponse>()
    val searchSuggestion: LiveData<SearchSuggestionResponse> = _searchSuggestion


    init {
        if (initialKeyword.isNotEmpty()) {
            tagList.value = listOf(Tag(initialKeyword))
        }

        if (showSuggestion) {
            viewModelScope.launch {
                inputDraft.asFlow()
                    .debounce(500)
                    .distinctUntilChanged()
                    .filter { it?.isNotEmpty() == true }
                    .collectLatest { word ->
                        try {
                            _searchSuggestion.value = Client.appApi.getSearchSuggestions(true, word)
                        } catch (ex: Exception) {

                        }
                    }
            }

            viewModelScope.launch {
                inputDraft.asFlow()
                    .distinctUntilChanged()
                    .filter { it.isNullOrEmpty() }
                    .collectLatest {
                        _searchSuggestion.value = SearchSuggestionResponse()
                    }
            }
        }

    }

    private val _searchIllustMangaEvent = MutableLiveData<Event<Long>>()
    private val _searchUserEvent = MutableLiveData<Event<Long>>()
    private val _searchNovelEvent = MutableLiveData<Event<Long>>()

    val searchIllustMangaEvent: LiveData<Event<Long>> = _searchIllustMangaEvent

    fun triggerSearchIllustMangaEvent(index: Long) {
        _searchIllustMangaEvent.postValue(Event(index))
    }


    val searchUserEvent: LiveData<Event<Long>> = _searchUserEvent

    fun triggerSearchUserEvent(index: Long) {
        _searchUserEvent.postValue(Event(index))
    }


    val searchNovelEvent: LiveData<Event<Long>> = _searchNovelEvent

    fun triggerSearchNovelEvent(index: Long) {
        _searchNovelEvent.postValue(Event(index))
    }

    fun triggerAllRefreshEvent() {
        val now = System.currentTimeMillis()
        triggerSearchIllustMangaEvent(now)
        triggerSearchUserEvent(now)
        triggerSearchNovelEvent(now)
    }

    fun buildSearchConfig(usersYori: Int?, objectType: String): SearchConfig {
        val tabIndex = if (objectType == ObjectType.ILLUST) {
            illustSelectedRadioTabIndex.value ?: 0
        } else {
            novelSelectedRadioTabIndex.value ?: 0
        }
        val sort = when (tabIndex) {
            0 -> {
                SortType.POPULAR_PREVIEW
            }

            1 -> {
                SortType.DATE_DESC
            }

            2 -> {
                SortType.DATE_ASC
            }

            else -> {
                SortType.POPULAR_DESC
            }
        }
        val yoriString = if ((usersYori ?: 0) > 0) {
            "${usersYori}users入り"
        } else {
            ""
        }
        return SearchConfig(
            keyword = tagList.value?.map { it.name }?.joinToString(separator = " ") ?: "",
            usersYori = yoriString,
            search_target = if (yoriString.isNotEmpty()) "exact_match_for_tags" else "partial_match_for_tags",
            sort = sort,
        )
    }
}