package ceui.pixiv

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.loxia.Client
import ceui.loxia.IllustResponse
import ceui.loxia.KListShow
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.IllustCardHolder
import ceui.refactor.ListItemHolder
import com.google.gson.Gson
import kotlinx.coroutines.launch


fun <Item, T: KListShow<Item>> Fragment.pixivListViewModel(
    loader: suspend () -> T,
    mapper: (Item) -> List<ListItemHolder>
): Lazy<PixivListViewModel<Item, T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PixivListViewModel(loader, mapper) as T
            }
        }
    }
}


class PixivListViewModel<Item, T: KListShow<Item>>(
    private val loader: suspend () -> T,
    private val mapper: (Item) -> List<ListItemHolder>
) : ViewModel() {

    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    private val _holders = MutableLiveData<List<ListItemHolder>>()
    val holders: LiveData<List<ListItemHolder>> = _holders

    private var _nextUrl: String? = null
    private val gson = Gson()

    init {
        refresh(RefreshHint.initialLoad())
    }


    fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                val batch = mutableListOf<ListItemHolder>()
                val illustResponse = loader()
                _nextUrl = illustResponse.nextPageUrl
                batch.addAll(illustResponse.displayList.flatMap(mapper))
                _holders.value = batch
                _refreshState.value = RefreshState.LOADED(hasNext = illustResponse.nextPageUrl?.isNotEmpty() == true)
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                ex.printStackTrace()
            }
        }
    }

    fun loadMore() {
        val nextUrl = _nextUrl ?: return
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = RefreshHint.loadMore())
                val responseBody = Client.appApi.generalGet(nextUrl)
                val jsonString = responseBody.string()
                val illustResponse = gson.fromJson(jsonString, IllustResponse::class.java)
                _nextUrl = illustResponse.next_url
                if (illustResponse.illusts.isNotEmpty()) {
                    val existing = (_holders.value ?: listOf()).toMutableList()
                    existing.addAll(illustResponse.illusts.map { IllustCardHolder(it) })
                    _holders.value = existing
                }
                _refreshState.value = RefreshState.LOADED(hasNext = illustResponse.nextPageUrl?.isNotEmpty() == true)
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                ex.printStackTrace()
            }
        }
    }

}