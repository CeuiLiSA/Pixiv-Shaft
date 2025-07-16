package ceui.pixiv.paging

import ceui.pixiv.ui.common.ListItemHolder
import kotlinx.coroutines.delay

class ArticleRepository {

    suspend fun loadArticles(page: Int, pageSize: Int): List<ListItemHolder> {
        delay(2000) // 模拟网络延迟

        val startId = (page - 1) * pageSize + 1
        val endId = startId + pageSize - 1

        return (startId..endId).map { id ->
            PagingItemHolder(PagingArticle(id, "Title $id", "Content of article $id"))
        }
    }
}
