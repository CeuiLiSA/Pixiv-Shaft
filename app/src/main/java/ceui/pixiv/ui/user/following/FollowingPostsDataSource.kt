package ceui.pixiv.ui.user.following

import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.loxia.UserPreview
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.user.UserPostHolder
import ceui.pixiv.ui.user.UserPreviewHolder

class FollowingPostsDataSource(
    private val responseStore: ResponseStore<IllustResponse> = ResponseStore(
        { "following-user-illusts-api" },
        1800 * 1000L,
        IllustResponse::class.java,
        { Client.appApi.followUserPosts("all") }
    )
) : DataSource<Illust, IllustResponse>(
    dataFetcher = { responseStore.retrieveData() },
    itemMapper = { illust -> listOf(UserPostHolder(illust)) },
    filter = { illust -> illust.isAuthurExist() }
)