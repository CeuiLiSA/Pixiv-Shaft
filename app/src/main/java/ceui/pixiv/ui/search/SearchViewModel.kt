package ceui.pixiv.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.loxia.Event
import ceui.loxia.Tag

class SearchViewModel(initialKeyword: String) : ViewModel() {


    val tagList = MutableLiveData<List<Tag>>()

    val selectedRadioTabIndex = MutableLiveData(0)

    val inputDraft = MutableLiveData("")

    init {
        if (initialKeyword.isNotEmpty()) {
            tagList.value = listOf(Tag(initialKeyword))
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
}