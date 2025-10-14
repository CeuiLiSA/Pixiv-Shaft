package ceui.pixiv.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import ceui.loxia.Event

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
            repository.errorEvent.postValue(Event(e))
            LoadResult.Error(e)
        }
    }
}
