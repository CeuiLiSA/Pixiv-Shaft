package ceui.pixiv.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import ceui.pixiv.ui.common.ListItemHolder

class ArticlePagingSource(
    private val repository: ArticleRepository
) : PagingSource<Int, ListItemHolder>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ListItemHolder> {
        return try {
            val page = params.key ?: 1
            val pageSize = params.loadSize

            val articles = repository.loadArticles(page, pageSize)

            LoadResult.Page(
                data = articles,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (articles.isEmpty()) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ListItemHolder>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
    }
}
