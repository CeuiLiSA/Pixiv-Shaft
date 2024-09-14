package ceui.pixiv.ui.search

import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder

class SearchIllustMangaDataSource(
    private val provider: () -> SearchConfig
) : DataSource<Illust, IllustResponse>(
    dataFetcher = {
        val config = provider()
        if (config.sort == SortType.POPULAR_PREVIEW) {
            Client.appApi.popularPreview(
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
            Client.appApi.searchIllustManga(
                word = word,
                sort = config.sort,
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
            )
        }
    },
    itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
) {
    override fun initialLoad(): Boolean {
        return provider().keyword.isNotEmpty()
    }
}