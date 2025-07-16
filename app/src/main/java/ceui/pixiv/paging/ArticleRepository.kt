package ceui.pixiv.paging

import ceui.pixiv.ui.common.ListItemHolder
import kotlinx.coroutines.delay

class ArticleRepository {
    suspend fun loadArticles(page: Int, pageSize: Int): List<ListItemHolder> {
        delay(2000) // 模拟网络延迟
        return (1..pageSize).map {
            val id = (page - 1) * pageSize + it
            PagingItemHolder(PagingArticle(id, "Title $id", "Content of article $id"))
        }
    }
}
