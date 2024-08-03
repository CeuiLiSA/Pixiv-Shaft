package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import com.google.gson.Gson

open class DataSource<Item, T: KListShow<Item>>(
    private val loader: suspend () -> T,
    private val mapper: (Item) -> List<ListItemHolder>
) {

    private val _holders = MutableLiveData<List<ListItemHolder>>()
    val holders: LiveData<List<ListItemHolder>> = _holders

    private var _nextUrl: String? = null
    protected val gson = Gson()


    private var classSpec: Class<T>? = null

    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    open suspend fun prepareRefreshResponse(): T {
        return loader()
    }

    open suspend fun refreshImpl(hint: RefreshHint) {
        try {
            _refreshState.value = RefreshState.LOADING(refreshHint = hint)
            val batch = mutableListOf<ListItemHolder>()
            val response = prepareRefreshResponse()
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

    open suspend fun loadMoreImpl() {
        try {
            val nextUrl = _nextUrl ?: return
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

    fun <ListItemHolderT : ListItemHolder> update(id: Long, invalidate: (ListItemHolderT) -> ListItemHolderT) {
        _holders.value?.let { currentHolders ->
            val index = currentHolders.indexOfFirst { it.getItemId() == id }
            if (index != -1) {
                try {
                    val target = currentHolders[index] as ListItemHolderT
                    val updated = invalidate(target)
                    val mutableList = currentHolders.toMutableList().apply {
                        set(index, updated)
                    }
                    _holders.value = mutableList
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }
}