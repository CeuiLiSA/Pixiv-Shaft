package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import com.google.gson.Gson

open class DataSource<Item, T: KListShow<Item>>(
    private val dataFetcher: suspend () -> T,
    private val itemMapper: (Item) -> List<ListItemHolder>
) {

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    val itemHolders: LiveData<List<ListItemHolder>> = _itemHolders

    private var _nextPageUrl: String? = null
    private val gson = Gson()

    private var responseClass: Class<T>? = null

    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    open suspend fun refreshData(hint: RefreshHint) {
        _refreshState.value = RefreshState.LOADING(refreshHint = hint)
        try {
            val response = dataFetcher()
            responseClass = response::class.java as Class<T>
            _nextPageUrl = response.nextPageUrl
            val holders = response.displayList.flatMap(itemMapper)
            _itemHolders.value = holders
            _refreshState.value = RefreshState.LOADED(
                hasContent = holders.isNotEmpty(),
                hasNext = _nextPageUrl?.isNotEmpty() == true
            )
        } catch (ex: Exception) {
            _refreshState.value = RefreshState.ERROR(ex)
            ex.printStackTrace()
        }
    }

    open suspend fun loadMoreData() {
        val nextPageUrl = _nextPageUrl ?: return
        _refreshState.value = RefreshState.LOADING(refreshHint = RefreshHint.loadMore())
        try {
            val responseBody = Client.appApi.generalGet(nextPageUrl)
            val responseJson = responseBody.string()
            val response = gson.fromJson(responseJson, responseClass)
            _nextPageUrl = response.nextPageUrl
            val newHolders = response.displayList.flatMap(itemMapper)
            if (newHolders.isNotEmpty()) {
                val existingHolders = (_itemHolders.value ?: listOf()).toMutableList()
                existingHolders.addAll(newHolders)
                _itemHolders.value = existingHolders
            }
            _refreshState.value = RefreshState.LOADED(
                hasContent = _itemHolders.value?.isNotEmpty() == true,
                hasNext = _nextPageUrl?.isNotEmpty() == true
            )
        } catch (ex: Exception) {
            _refreshState.value = RefreshState.ERROR(ex)
            ex.printStackTrace()
        }
    }

    fun <HolderT : ListItemHolder> updateItem(id: Long, update: (HolderT) -> HolderT) {
        _itemHolders.value?.let { currentHolders ->
            val index = currentHolders.indexOfFirst { it.getItemId() == id }
            if (index != -1) {
                try {
                    val target = currentHolders[index] as HolderT
                    val updated = update(target)
                    val updatedHolders = currentHolders.toMutableList().apply {
                        set(index, updated)
                    }
                    _itemHolders.value = updatedHolders
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }
}
