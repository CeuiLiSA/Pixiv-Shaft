package ceui.pixiv

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
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

    private var classSpec: Class<T>? = null

    fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                val batch = mutableListOf<ListItemHolder>()
                val response = loader()
                classSpec = response::class.java as Class<T>
                _nextUrl = response.nextPageUrl
                batch.addAll(response.displayList.flatMap(mapper))
                _holders.value = batch
                _refreshState.value = RefreshState.LOADED(
                    hasContent = response.displayList.isNotEmpty(),
                    hasNext = response.nextPageUrl?.isNotEmpty() == true
                )
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
                val response = gson.fromJson(jsonString, classSpec)
                _nextUrl = response.nextPageUrl
                if (response.displayList.isNotEmpty()) {
                    val existing = (_holders.value ?: listOf()).toMutableList()
                    existing.addAll(response.displayList.flatMap(mapper))
                    _holders.value = existing
                }
                _refreshState.value = RefreshState.LOADED(
                    hasContent = _holders.value?.isNotEmpty() == true,
                    hasNext = response.nextPageUrl?.isNotEmpty() == true
                )
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                ex.printStackTrace()
            }
        }
    }

    fun update(id: Long, validate: (ListItemHolder) -> ListItemHolder) {
        _holders.value?.let { currentHolders ->
            val target = currentHolders.firstOrNull { it.getItemId() == id }
            if (target != null) {
                val index = currentHolders.indexOf(target)
                val updated = validate(target)
                val mutableList = currentHolders.toMutableList()
                mutableList.removeAt(index)
                mutableList.add(index, updated)
                _holders.value = mutableList
            }
        }
    }

}