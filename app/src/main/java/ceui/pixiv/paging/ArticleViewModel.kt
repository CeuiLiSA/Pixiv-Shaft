package ceui.pixiv.paging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.pixiv.db.EntityType
import ceui.pixiv.db.RecordType
import kotlinx.coroutines.flow.map

class ArticleViewModel(
    private val db: AppDatabase,
) : ViewModel() {

    private val repository = ArticleRepository()
    private val recordType = RecordType.PAGING_DATA

    @OptIn(ExperimentalPagingApi::class)
    val pager = Pager(
        config = PagingConfig(
            pageSize = 30,
            initialLoadSize = 30,  // 只加载 1 页
            prefetchDistance = 0   // 滑到底才触发 LoadType.APPEND
        ),
        remoteMediator = ArticleRemoteMediator(db, repository, recordType, EntityType.ILLUST),
        pagingSourceFactory = { db.generalDao().pagingSource(recordType) },
    ).flow
        .map { pagingData ->
            pagingData.map {
                it.typedObject<Illust>()
            }
        }
        .cachedIn(viewModelScope)
}
