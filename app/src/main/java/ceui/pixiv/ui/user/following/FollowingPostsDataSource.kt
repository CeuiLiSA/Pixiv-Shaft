package ceui.pixiv.ui.user.following

import ceui.lisa.utils.Params
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
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.user.UserPostHolder
import ceui.pixiv.ui.user.UserPreviewHolder

class FollowingPostsDataSource(
    private val args: FollowingPostFragmentArgs,
) : DataSource<Illust, IllustResponse>(
    dataFetcher = { hint -> Client.appApi.followUserPosts(args.objectType, args.restrictType ?: Params.TYPE_ALL) },
    responseStore = createResponseStore({ "following-user-${args.objectType}-api-${args.restrictType}" }),
    itemMapper = { illust -> listOf(UserPostHolder(illust)) },
    filter = { illust -> illust.isAuthurExist() }
)