package ceui.pixiv.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import ceui.loxia.Illust

class ArticlePagingSource(
    private val repository: ArticleRepository
) : PagingSource<String, Illust>() {

    override fun getRefreshKey(state: PagingState<String, Illust>): String? {
        return null
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Illust> {
        return try {
            val nextPageUrl: String? = params.key // null 表示第一页

            val response = repository.loadImpl(nextPageUrl)

            LoadResult.Page(
                data = response.displayList,
                prevKey = null, // 通常 cursor-based 不支持 prevKey，留空
                nextKey = response.nextPageUrl // 下一页的 URL
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
