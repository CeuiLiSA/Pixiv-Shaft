package ceui.pixiv.ui.discover

import ceui.loxia.Client
import ceui.loxia.HomeData
import ceui.loxia.HomeOneLine
import ceui.loxia.Illust
import ceui.loxia.MainBody
import ceui.loxia.NextPageSpec
import ceui.loxia.Novel
import ceui.loxia.ObjectType
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.user.UserPostHolder
import com.google.gson.Gson

class DiscoverAllViewModel : HoldersViewModel() {

    private var _nextPageSpec: NextPageSpec? = null
    private val gson = Gson()
    private val responseStore = createResponseStore<HomeData>({ "home-all" })

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)

        val resp = responseStore.loadFromCache() ?: Client.appApi.getHomeAll().also {
            responseStore.writeToCache(it)
        }
        _nextPageSpec = resp.next_params
        val records = mapHolders(resp.displayList)
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
        val records = mapHolders(resp.displayList)
        _itemHolders.value = ((_itemHolders.value ?: listOf()) + records)
        _refreshState.value = RefreshState.LOADED(
            hasContent = true,
            hasNext = _nextPageSpec != null
        )
    }

    private fun mapHolders(items: List<HomeOneLine>): List<ListItemHolder> {
        return items.mapNotNull { spec ->
            val appModel = spec.thumbnails?.firstOrNull()?.app_model
            val json = appModel?.let { gson.toJson(it) }.takeIf { !it.isNullOrEmpty() } ?: return@mapNotNull null

            when (spec.kind) {
                ObjectType.NOVEL -> gson.fromJson(json, Novel::class.java)?.let { NovelCardHolder(it) }
                ObjectType.ILLUST, ObjectType.MANGA -> gson.fromJson(json, Illust::class.java)?.let { UserPostHolder(it) }
                else -> null
            }
        }
    }


    init {
        refresh(RefreshHint.InitialLoad)
    }
}