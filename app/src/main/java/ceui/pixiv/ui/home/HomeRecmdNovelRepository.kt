package ceui.pixiv.ui.home

import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.paging.PagingMediatorRepository
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.NovelCardHolder

class HomeRecmdNovelRepository : PagingMediatorRepository<Novel>() {
    override val recordType: Int
        get() = RecordType.PAGING_DATA_HOME_DISCOVER_RECOMMEND_NOVEL

    override suspend fun loadFirst(): KListShow<Novel> {
        return Client.appApi.getRecmdNovels()
    }

    override fun mapper(entity: GeneralEntity): List<ListItemHolder> {
        val novel = entity.typedObject<Novel>()
        return if (novel.visible == true) {
            return listOf(NovelCardHolder(novel))
        } else {
            emptyList()
        }
    }
}