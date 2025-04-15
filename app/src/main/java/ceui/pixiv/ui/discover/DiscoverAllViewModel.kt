package ceui.pixiv.ui.discover

import ceui.loxia.Client
import ceui.loxia.HomeData
import ceui.loxia.MainBody
import ceui.loxia.NextPageSpec
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.createResponseStore

class DiscoverAllViewModel : HoldersViewModel() {

    private var _nextPageSpec: NextPageSpec? = null

    private val responseStore = createResponseStore<HomeData>({ "home-all" })


    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)

        val resp = responseStore.loadFromCache() ?: Client.appApi.getHomeAll().also {
            responseStore.writeToCache(it)
        }
        _nextPageSpec = resp.next_params
        val records = resp.displayList.map { SpecHolder(it) }
        _itemHolders.value = records
        _refreshState.value = RefreshState.LOADED(
            hasContent = records.isNotEmpty(),
            hasNext = _nextPageSpec != null
        )
    }

    override suspend fun loadMoreImpl() {
        super.loadMoreImpl()
        val nextPageSpec = _nextPageSpec ?: return
        val resp = Client.appApi.getHomeAll(MainBody(next_params = nextPageSpec))
        _nextPageSpec = resp.next_params
        val records = resp.displayList.map { SpecHolder(it) }
        _itemHolders.value = ((_itemHolders.value ?: listOf()) + records)
        _refreshState.value = RefreshState.LOADED(
            hasContent = true,
            hasNext = _nextPageSpec != null
        )
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }
}