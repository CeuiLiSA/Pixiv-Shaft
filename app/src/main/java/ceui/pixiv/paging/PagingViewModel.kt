package ceui.pixiv.paging

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.utils.NetworkStateManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
class PagingViewModel<ObjectT : ModelObject>(
    private val db: AppDatabase,
    private val networkStateManager: NetworkStateManager,
    private val repository: PagingRepository<ObjectT>,
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)

    val pager: Flow<PagingData<ListItemHolder>> =
        combine(
            networkStateManager.googleAccessRecoveredFlow,
            refreshTrigger
        ) { canAccess, _ -> canAccess } // refreshTrigger 只用来触发
            .flatMapLatest { canAccess ->
                when (repository) {
                    is PagingMediatorRepository -> createMediatorPager(repository, canAccess)
                    is PagingAPIRepository -> createApiPager(repository, canAccess)
                    else -> throw IllegalArgumentException("Unsupported repository type")
                }
            }
            .cachedIn(viewModelScope)

    private fun createMediatorPager(
        repository: PagingMediatorRepository<ObjectT>,
        canAccess: Boolean
    ): Flow<PagingData<ListItemHolder>> {
        return if (!canAccess) {
            Pager(
                config = defaultPagingConfig(),
                pagingSourceFactory = { NoOpPagingSource<GeneralEntity>() }
            ).flow.map { it.flatMap(repository::mapper) }
        } else {
            Pager(
                config = defaultPagingConfig(),
                remoteMediator = PagingRemoteMediator(db, repository, repository.recordType),
                pagingSourceFactory = {
                    db.generalDao().pagingSource(repository.recordType)
                }
            ).flow.map { it.flatMap(repository::mapper) }
        }
    }

    private fun createApiPager(
        repository: PagingAPIRepository<ObjectT>,
        canAccess: Boolean
    ): Flow<PagingData<ListItemHolder>> {
        return if (!canAccess) {
            Pager(
                config = defaultPagingConfig(),
                pagingSourceFactory = { NoOpPagingSource<ObjectT>() }
            ).flow.map { it.flatMap(repository::mapper) }
        } else {
            Pager(
                config = defaultPagingConfig(),
                pagingSourceFactory = { PagingAPISource(repository) }
            ).flow.map { it.flatMap(repository::mapper) }
        }
    }

    private fun defaultPagingConfig(): PagingConfig {
        return PagingConfig(
            pageSize = 30,
            initialLoadSize = 30,
            prefetchDistance = 5,
        )
    }

    fun refresh() {
        refreshTrigger.value++
    }

    val recordType: Int? get() = (repository as? PagingMediatorRepository)?.recordType
}


inline fun <ObjectT : ModelObject> Fragment.pagingViewModel(
    noinline repositoryProducer: () -> PagingRepository<ObjectT>,
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
    noinline repositoryProducer: (ArgsT) -> PagingRepository<ObjectT>,
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
