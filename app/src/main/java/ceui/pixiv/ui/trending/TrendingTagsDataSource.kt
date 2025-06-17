package ceui.pixiv.ui.trending

import ceui.loxia.Client
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.createResponseStore

class TrendingTagsDataSource(
    private val objectType: String,
) : DataSource<TrendingTag, TrendingTagsResponse>(
    dataFetcher = { Client.appApi.trendingTags(objectType) },
    responseStore = createResponseStore({ "trending-tags-${objectType}-api" }),
    itemMapper = { trendingTag -> listOf(TrendingTagHolder(trendingTag)) }
)