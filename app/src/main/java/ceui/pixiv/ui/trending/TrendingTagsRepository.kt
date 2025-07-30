package ceui.pixiv.ui.trending

import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.ObjectType
import ceui.loxia.TrendingTag
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.paging.PagingMediatorRepository
import ceui.pixiv.ui.common.ListItemHolder

class TrendingTagsRepository(private val objectType: String) :
    PagingMediatorRepository<TrendingTag>() {
    override val recordType: Int
        get() {
            return if (objectType == ObjectType.ILLUST) {
                RecordType.PAGING_DATA_HOME_TRENDING_TAG_ILLUST
            } else {
                RecordType.PAGING_DATA_HOME_TRENDING_TAG_NOVEL
            }
        }

    override suspend fun loadFirst(): KListShow<TrendingTag> {
        return Client.appApi.trendingTags(objectType)
    }

    override fun mapper(entity: GeneralEntity): List<ListItemHolder> {
        return listOf(TrendingTagHolder(entity.typedObject()))
    }
}