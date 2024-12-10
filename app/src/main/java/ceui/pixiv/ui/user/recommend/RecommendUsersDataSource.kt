package ceui.pixiv.ui.user.recommend

import ceui.loxia.Client
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.loxia.UserPreview
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.user.UserPreviewHolder

class RecommendUsersDataSource(
    private val responseStore: ResponseStore<UserPreviewResponse> = ResponseStore(
        { "recommend-users-api" },
        1800 * 1000L,
        UserPreviewResponse::class.java,
        { Client.appApi.recommendedUsers() }
    )
) : DataSource<UserPreview, UserPreviewResponse>(
    dataFetcher = { hint -> responseStore.retrieveData(hint) },
    itemMapper = { preview -> listOf(UserPreviewHolder(preview)) }
)