package ceui.pixiv.ui.trending

import ceui.loxia.Client
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ResponseStore

class TrendingTagsDataSource(
    private val args: TrendingTagsFragmentArgs,
    private val responseStore: ResponseStore<TrendingTagsResponse> = ResponseStore(
        { "trending-tags-${args.objectType}-api" },
        1800 * 1000L,
        TrendingTagsResponse::class.java,
        { Client.appApi.trendingTags(args.objectType) }
    )
) : DataSource<TrendingTag, TrendingTagsResponse>(
    dataFetcher = { responseStore.retrieveData() },
    itemMapper = { trendingTag -> listOf(TrendingTagHolder(trendingTag)) }
)