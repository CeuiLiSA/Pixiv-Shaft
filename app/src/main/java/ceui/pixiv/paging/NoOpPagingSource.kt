package ceui.pixiv.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import ceui.pixiv.db.GeneralEntity


class NoOpPagingSource : PagingSource<String, GeneralEntity>() {

    override fun getRefreshKey(state: PagingState<String, GeneralEntity>): String? {
        return null
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, GeneralEntity> {
        return LoadResult.Error(RuntimeException("Hello world"))
    }
}
