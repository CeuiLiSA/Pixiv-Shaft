package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

open class DataSource<Item, T: KListShow<Item>>(
    private val dataFetcher: suspend (hint: RefreshHint) -> T,
    itemMapper: (Item) -> List<ListItemHolder>,
    private val filter: (Item) -> Boolean = { _ -> true }
) {
    private var _variableItemMapper: ((Item) -> List<ListItemHolder>)? = null

    init {
        this._variableItemMapper = itemMapper
    }

    private val currentProtoItems = mutableListOf<Item>()

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    val itemHolders: LiveData<List<ListItemHolder>> = _itemHolders

    private val _liveNextUrl = MutableLiveData<String>()
    val liveNextUrl: LiveData<String> = _liveNextUrl

    private var _nextPageUrl: String? = null
    private val gson = Gson()

    private var responseClass: Class<T>? = null

    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    open suspend fun refreshData(hint: RefreshHint) {
        _refreshState.value = RefreshState.LOADING(refreshHint = hint)
        try {
            if (hint == RefreshHint.ErrorRetry) {
                delay(300L)
            }
            val response = withContext(Dispatchers.IO) {
                dataFetcher(hint)
            }
            currentProtoItems.clear()
            responseClass = response::class.java as Class<T>
            _nextPageUrl = response.nextPageUrl
            _liveNextUrl.value = response.nextPageUrl
            currentProtoItems.addAll(response.displayList)
            mapProtoItemsToHolders()
            _refreshState.value = RefreshState.LOADED(
                hasContent = _itemHolders.value?.isNotEmpty() == true,
                hasNext = _nextPageUrl?.isNotEmpty() == true
            )
        } catch (ex: Exception) {
            _refreshState.value = RefreshState.ERROR(ex)
            Timber.e(ex)
        }
    }

    open suspend fun loadMoreData() {
        val nextPageUrl = _nextPageUrl ?: return
        _refreshState.value = RefreshState.LOADING(refreshHint = RefreshHint.LoadMore)
        try {
            val response = withContext(Dispatchers.IO) {
                val responseBody = Client.appApi.generalGet(nextPageUrl)
                val responseJson = responseBody.string()
                gson.fromJson(responseJson, responseClass)
            }
            responseClass = response::class.java as Class<T>
            _nextPageUrl = response.nextPageUrl
            _liveNextUrl.value = response.nextPageUrl
            if (response.displayList.isNotEmpty()) {
                currentProtoItems.addAll(response.displayList)
                mapProtoItemsToHolders()
            }
            _refreshState.value = RefreshState.LOADED(
                hasContent = _itemHolders.value?.isNotEmpty() == true,
                hasNext = _nextPageUrl?.isNotEmpty() == true
            )
        } catch (ex: Exception) {
            _refreshState.value = RefreshState.ERROR(ex)
            Timber.e(ex)
        }
    }

    private fun updateOffsetInUrl(url: String, newOffset: Int): String {
        val regex = """(offset=)(\d+)""".toRegex()
        return if (regex.containsMatchIn(url)) {
            // 替换现有的 offset 参数
            regex.replace(url) { matchResult ->
                "${matchResult.groupValues[1]}$newOffset"
            }
        } else {
            // 如果 URL 中没有 offset 参数，直接添加它
            if (url.contains("?")) {
                "$url&offset=$newOffset"
            } else {
                "$url?offset=$newOffset"
            }
        }
    }

    suspend fun loadOffsetData(pageIndex: Int) {
        val nextPageUrl = _nextPageUrl ?: return
        Common.showLog("dasasds aaa ${nextPageUrl}")
        val newNextUrl = updateOffsetInUrl(nextPageUrl, pageIndex * 30)
        _nextPageUrl = newNextUrl
        _liveNextUrl.value = newNextUrl
        currentProtoItems.clear()
        Common.showLog("dasasds bbb ${newNextUrl}")
        loadMoreData()
    }

    private fun mapProtoItemsToHolders() {
        val mapper = _variableItemMapper ?: return
        val holders = currentProtoItems
            .filter { item ->
                filter(item)
            }
            .flatMap(mapper)
        _itemHolders.value = holders
    }

    fun pickProtoItems(): List<Item> {
        return currentProtoItems
    }

    fun updateMapper(mapper: (Item) -> List<ListItemHolder>) {
        _itemHolders.value = listOf()
        this._variableItemMapper = mapper
        mapProtoItemsToHolders()
    }

    protected fun pickItemHolders(): MutableLiveData<List<ListItemHolder>> {
        return _itemHolders
    }

    open fun initialLoad(): Boolean {
        return true
    }
}
