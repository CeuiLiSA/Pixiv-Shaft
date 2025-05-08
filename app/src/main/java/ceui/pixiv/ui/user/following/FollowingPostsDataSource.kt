package ceui.pixiv.ui.user.following

import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.user.UserPostHolder
import com.tencent.mmkv.MMKV

class FollowingPostsDataSource(
    private val args: FollowingPostFragmentArgs,
) : DataSource<Illust, IllustResponse>(
    dataFetcher = {
        val prefStore = MMKV.mmkvWithID("api-cache-${SessionManager.loggedInUid}")
        val resp =
            Client.appApi.followUserPosts(args.objectType, args.restrictType ?: Params.TYPE_ALL)

        val key = "latest-${args.objectType}-id"

        val lastLatestIllustId = prefStore.getLong(key, 0L)
        val lastIndex = resp.displayList.indexOfFirst { it.id == lastLatestIllustId }
        if (lastIndex > 0) {
        }

        resp.displayList.getOrNull(0)?.let { latestIllust ->
            prefStore.putLong(key, latestIllust.id)
        }
        resp
    },
    responseStore = createResponseStore({ "following-user-${args.objectType}-api-${args.restrictType}" }),
    itemMapper = { illust -> listOf(UserPostHolder(illust)) },
    filter = { illust -> illust.isAuthurExist() }
)