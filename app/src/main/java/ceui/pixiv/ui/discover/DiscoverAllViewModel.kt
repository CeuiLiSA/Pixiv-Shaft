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
import kotlin.collections.mutableListOf

class DiscoverAllViewModel : HoldersViewModel() {

    private var _nextPageSpec: NextPageSpec? = null
    private val gson = Gson()
    private val responseStore = createResponseStore<HomeData>({ "home-all" })

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)

        val cached = responseStore.loadFromCache()
        val resp = if (cached != null && !responseStore.isCacheExpired()) {
            cached
        } else {
            Client.appApi.getHomeAll().also {
                responseStore.writeToCache(it)
            }
        }
        _nextPageSpec = resp.next_params
        val records = mapHolders(resp.displayList)
        _itemHolders.value = records
        _refreshState.value = RefreshState.LOADED(
            hasContent = records.isNotEmpty(), hasNext = _nextPageSpec != null
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
            hasContent = true, hasNext = _nextPageSpec != null
        )
    }

    private fun mapHolders(items: List<HomeOneLine>): List<ListItemHolder> {
        val ret = mutableListOf<ListItemHolder>()
        items.forEach { spec ->
            when (spec.kind) {
                ObjectType.NOVEL -> {
                    parseAppModel<Novel>(spec.thumbnails?.firstOrNull()?.app_model)?.let {
                        NovelCardHolder(it).also {
                            ret.add(it)
                        }
                    }
                }

                ObjectType.ILLUST, ObjectType.MANGA -> {
                    parseAppModel<Illust>(spec.thumbnails?.firstOrNull()?.app_model)?.let {
                        UserPostHolder(it).also {
                            ret.add(it)
                        }
                    }
                }


                "tags_carousel", "ranking" -> {
                    val title = if (spec.kind == "ranking") {
                        "Daily Ranking (${spec.ranking_date})"
                    } else {
                        "Hot Tags"
                    }

                    val illustList = mutableListOf<Illust>()
                    spec.thumbnails?.forEach { thumbnail ->
                        parseAppModel<Illust>(thumbnail.app_model)?.let { illustList.add(it) }
                    }
                    ret.add(RankPreviewListHolder(title, illustList))
                }

                "pixivision" -> {
                    ret.add(ArticlePreviewListHolder("Pixivision", spec.thumbnails.orEmpty()))
                }

                else -> null
            }
        }
        return ret
    }

    private inline fun <reified T> parseAppModel(app_model: Any?): T? {
        val json = app_model?.let { gson.toJson(it) }
        return json?.takeIf { it.isNotEmpty() }?.let { gson.fromJson(it, T::class.java) }
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }
}