package ceui.pixiv.ui.user.recommend

import ceui.loxia.Client
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.loxia.UserPreview
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.user.UserPreviewHolder

class RecommendUsersDataSource : DataSource<UserPreview, UserPreviewResponse>(
    dataFetcher = { Client.appApi.recommendedUsers() },
    responseStore = createResponseStore({ "recommend-users-api" }),
    itemMapper = { preview -> listOf(UserPreviewHolder(preview)) }
)