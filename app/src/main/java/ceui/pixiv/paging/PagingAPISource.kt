package ceui.pixiv.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState

class PagingAPISource<ObjectT : Any>(
    private val repository: PagingAPIRepository<ObjectT>
) : PagingSource<String, ObjectT>() {

    override fun getRefreshKey(state: PagingState<String, ObjectT>): String? {
        return null
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, ObjectT> {
        return try {
            val nextPageUrl: String? = params.key

            val response = repository.load(nextPageUrl)

            LoadResult.Page(
                data = response.displayList,
                prevKey = null,
                nextKey = response.nextPageUrl
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
