package ceui.pixiv.ui.trending

import ceui.loxia.Client
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.createResponseStore

class TrendingTagsDataSource(
    private val args: TrendingTagsFragmentArgs,
) : DataSource<TrendingTag, TrendingTagsResponse>(
    dataFetcher = { Client.appApi.trendingTags(args.objectType) },
    responseStore = createResponseStore({ "trending-tags-${args.objectType}-api" }),
    itemMapper = { trendingTag -> listOf(TrendingTagHolder(trendingTag)) }
)