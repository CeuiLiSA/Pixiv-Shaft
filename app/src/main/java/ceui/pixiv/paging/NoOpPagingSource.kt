package ceui.pixiv.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState


class NoOpPagingSource<ObjectT : Any> : PagingSource<String, ObjectT>() {

    override fun getRefreshKey(state: PagingState<String, ObjectT>): String? {
        return null
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, ObjectT> {
        return LoadResult.Error(RuntimeException("Hello world"))
    }
}
