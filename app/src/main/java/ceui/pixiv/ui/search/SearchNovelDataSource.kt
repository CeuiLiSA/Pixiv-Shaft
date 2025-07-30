package ceui.pixiv.ui.search

import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.NovelCardHolder

class SearchNovelDataSource(
    private val provider: () -> SearchConfig
) : PagingAPIRepository<Novel>() {
//    override fun initialLoad(): Boolean {
//        return provider().keyword.isNotEmpty()
//    }

    override suspend fun loadFirst(): KListShow<Novel> {
        val config = provider()
        return if (config.sort == SortType.POPULAR_PREVIEW) {
            Client.appApi.popularPreviewNovel(
                word = config.keyword,
                sort = config.sort,
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
            )
        } else {
            val word = if (config.usersYori.isNotEmpty()) {
                config.keyword + " " + config.usersYori
            } else {
                config.keyword
            }
            Client.appApi.searchNovel(
                word = word,
                sort = config.sort,
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
            )
        }
    }

    override fun mapper(entity: Novel): List<ListItemHolder> {
        return listOf(NovelCardHolder(entity))
    }
}