package ceui.pixiv.ui.article

import ceui.lisa.utils.Params
import ceui.loxia.Article
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.paging.PagingMediatorRepository
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.PvisionCardHolder

class ArticleRepository : PagingMediatorRepository<Article>() {

    override val recordType: Int
        get() = RecordType.PAGING_DATA_ARTICLE_ALL

    override suspend fun loadFirst(): KListShow<Article> {
        return Client.appApi.pixivsionArticles(Params.TYPE_ALL)
    }

    override fun mapper(entity: GeneralEntity): List<ListItemHolder> {
        val article = entity.typedObject<Article>()
        return listOf(PvisionCardHolder(article))
    }
}