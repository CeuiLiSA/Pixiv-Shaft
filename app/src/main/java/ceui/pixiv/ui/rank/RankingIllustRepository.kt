package ceui.pixiv.ui.rank

import androidx.lifecycle.LiveData
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.loxia.stableHash
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder

class RankingIllustRepository(
    private val mode: String,
    private val rankDay: LiveData<String?>,
) : PagingAPIRepository<Illust>() {
    override val recordType: Int
        get() = RecordType.PAGING_DATA_ILLUST_RANKING + stableHash(mode)

    override suspend fun loadFirst(): KListShow<Illust> {
        return Client.appApi.getRankingIllusts(mode, rankDay.value)
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