package ceui.pixiv.paging

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.flatMap
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.ModelObject
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.utils.NetworkStateManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class PagingViewModel<ObjectT : ModelObject>(
    private val db: AppDatabase,
    private val networkStateManager: NetworkStateManager,
    private val repository: PagingAPIRepository<ObjectT>,
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)
    private val canAccessGoogle = networkStateManager.canAccessGoogle.asFlow()

    @OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
    val pager = combine(refreshTrigger, canAccessGoogle) { _, canAccess ->
        canAccess
    }.flatMapLatest { canAccess ->
        if (!canAccess) {
            Pager(
                config = PagingConfig(
                    pageSize = 30,
                    initialLoadSize = 30,
                    prefetchDistance = 0
                ),
                pagingSourceFactory = { NoOpPagingSource() }
            ).flow.map { pagingData ->
                pagingData.flatMap(repository::mapper)
            }
        } else {
            Pager(
                config = PagingConfig(
                    pageSize = 30,
                    initialLoadSize = 30,
                    prefetchDistance = 0
                ),
                remoteMediator = PagingRemoteMediator(db, repository, repository.recordType),
                pagingSourceFactory = { db.generalDao().pagingSource(repository.recordType) }
            ).flow.map { pagingData ->
                pagingData.flatMap(repository::mapper)
            }
        }
    }.cachedIn(viewModelScope)

    fun refresh() {
        refreshTrigger.value++
    }

    val recordType: Int get() = repository.recordType
}

inline fun <ObjectT : ModelObject> Fragment.pagingViewModel(
    noinline repositoryProducer: () -> PagingAPIRepository<ObjectT>,
): Lazy<PagingViewModel<ObjectT>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val database = AppDatabase.getAppDatabase(requireContext())
                val networkStateManager = requireNetworkStateManager()
                val repository = repositoryProducer()
                return PagingViewModel(database, networkStateManager, repository) as T
            }
        }
    }
}


inline fun <ArgsT, ObjectT : ModelObject> Fragment.pagingViewModel(
    noinline argsProducer: () -> ArgsT,
    noinline repositoryProducer: (ArgsT) -> PagingAPIRepository<ObjectT>,
): Lazy<PagingViewModel<ObjectT>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val database = AppDatabase.getAppDatabase(requireContext())
                val networkStateManager = requireNetworkStateManager()
                val args = argsProducer()
                val repository = repositoryProducer(args)
                return PagingViewModel(database, networkStateManager, repository) as T
            }
        }
    }
}
