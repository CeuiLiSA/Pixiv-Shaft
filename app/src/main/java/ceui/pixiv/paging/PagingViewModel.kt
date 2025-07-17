package ceui.pixiv.paging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.ModelObject

class PagingViewModel<ObjectT : ModelObject>(
    private val db: AppDatabase,
    private val repository: PagingAPIRepository<ObjectT>,
) : ViewModel() {

    @OptIn(ExperimentalPagingApi::class)
    val pager = Pager(
        config = PagingConfig(
            pageSize = 30,
            initialLoadSize = 30,  // 只加载 1 页
            prefetchDistance = 0   // 滑到底才触发 LoadType.APPEND
        ),
        remoteMediator = PagingRemoteMediator(db, repository, repository.recordType),
        pagingSourceFactory = { db.generalDao().pagingSource(repository.recordType) },
    ).flow
        .cachedIn(viewModelScope)
}
