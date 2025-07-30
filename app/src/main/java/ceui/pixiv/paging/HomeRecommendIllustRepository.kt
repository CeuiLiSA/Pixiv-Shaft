package ceui.pixiv.paging

import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.loxia.ObjectType
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder

class HomeRecommendIllustRepository(private val objectType: String) :
    PagingMediatorRepository<Illust>() {

    override val recordType: Int
        get() {
            return if (objectType == ObjectType.ILLUST) {
                RecordType.PAGING_DATA_HOME_DISCOVER_RECOMMEND_ILLUST
            } else {
                RecordType.PAGING_DATA_HOME_DISCOVER_RECOMMEND_MANGA
            }
        }

    override suspend fun loadFirst(): KListShow<Illust> {
        return Client.appApi.getHomeData(objectType)
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
