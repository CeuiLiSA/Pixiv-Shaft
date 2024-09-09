package ceui.pixiv.ui.search

import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.NovelCardHolder

class SearchNovelDataSource(
    private val provider: () -> SearchConfig
) : DataSource<Novel, NovelResponse>(
    dataFetcher = {
        val config = provider()
        if (config.sort == SortType.POPULAR_PREVIEW) {
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
    },
    itemMapper = { novel -> listOf(NovelCardHolder(novel)) }
) {
    override fun initialLoad(): Boolean {
        return provider().keyword.isNotEmpty()
    }
}