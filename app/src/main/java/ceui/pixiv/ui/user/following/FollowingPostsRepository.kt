package ceui.pixiv.ui.user.following

import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.paging.PagingMediatorRepository
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.user.UserPostHolder

class FollowingPostsRepository(private val args: FollowingPostFragmentArgs) :
    PagingMediatorRepository<Illust>() {

    override val recordType: Int
        get() {
            return if (args.restrictType == Params.TYPE_PUBLIC) {
                RecordType.PAGING_DATA_HOME_PUBLIC_FOLLOWING_ILLUST
            } else {
                RecordType.PAGING_DATA_HOME_PRIVATE_FOLLOWING_ILLUST
            }
        }

    override suspend fun loadFirst(): KListShow<Illust> {
        return Client.appApi.followUserPosts(args.objectType, args.restrictType ?: Params.TYPE_ALL)
    }

    override fun mapper(entity: GeneralEntity): List<ListItemHolder> {
        val illust = entity.typedObject<Illust>()
        if (illust.isAuthurExist()) {
            return listOf(UserPostHolder(illust))
        }
        return emptyList()
    }
}