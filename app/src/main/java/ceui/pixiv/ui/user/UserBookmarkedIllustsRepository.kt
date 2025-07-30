package ceui.pixiv.ui.user

import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.paging.PagingMediatorRepository
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder

class UserBookmarkedIllustsRepository(private val args: UserBookmarkedIllustsFragmentArgs) :
    PagingMediatorRepository<Illust>() {
    override val recordType: Int
        get() {
            return if (args.restrictType == Params.TYPE_PUBLIC) {
                RecordType.PAGING_DATA_BOOKMARKED_ILLUST_PUBLIC
            } else {
                RecordType.PAGING_DATA_BOOKMARKED_ILLUST_PRIVATE
            }
        }

    override suspend fun loadFirst(): KListShow<Illust> {
        return Client.appApi.getUserBookmarkedIllusts(
            args.userId,
            args.restrictType ?: Params.TYPE_PUBLIC
        )
    }

    override fun mapper(entity: GeneralEntity): List<ListItemHolder> {
        val illust = entity.typedObject<Illust>()
        return if (illust.isAuthurExist()) {
            listOf(IllustCardHolder(illust))
        } else {
            emptyList()
        }
    }
}