package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.repo.HybridRepository
import ceui.pixiv.ui.common.repo.LoadResult
import ceui.pixiv.ui.common.repo.Repository
import ceui.pixiv.ui.common.repo.ResponseStoreRepository
import ceui.pixiv.utils.TokenGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber

open class ValueContent<ValueT>(
    private val coroutineScope: CoroutineScope,
    private val repository: Repository<ValueT>,
) : RefreshOwner {

    private val _result = MutableLiveData<LoadResult<ValueT>>()
    val result: LiveData<LoadResult<ValueT>> = _result

    private val _refreshState = MutableLiveData<RefreshState>()
    override val refreshState: LiveData<RefreshState> = _refreshState

    private val _taskMutex = Mutex() // 互斥锁，防止重复刷新

    override fun refresh(hint: RefreshHint) {
        if (!_taskMutex.tryLock()) {
            Timber.e("ValueContent refresh tryLock returned")
            return // 如果当前已有刷新任务在执行，则直接返回
        }

        val requestToken = TokenGenerator.generateToken()

        Timber.e("ValueContent refresh tryLock passed: ${requestToken}")
        coroutineScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                if (hint == RefreshHint.ErrorRetry) {
                    delay(300L)
                }


                if (hint == RefreshHint.InitialLoad) {
                    (repository as? HybridRepository<ValueT>)?.loadFromCache()?.let {
                        _result.value = it
                        _refreshState.value = RefreshState.LOADED(
                            hasContent = it.data != null && hasContent(it.data),
                            hasNext = false
                        )
                    }
                }

                val responseStore = (repository as? ResponseStoreRepository<*>)?.responseStore

                if (hint == RefreshHint.PullToRefresh ||
                    hint == RefreshHint.ErrorRetry ||
                    hint == RefreshHint.FetchingLatest ||
                    responseStore == null ||
                    responseStore.isCacheExpired()
                ) {
                    if ((hint == RefreshHint.InitialLoad || hint == RefreshHint.FetchingLatest) && _result.value != null) {
                        delay(600L)
                        _refreshState.value = RefreshState.FETCHING_LATEST()
                        delay(1000L)
                    }

                    val response = withContext(Dispatchers.IO) {
                        repository.load()
                    }
                    _result.value = response

                    _refreshState.value = RefreshState.LOADED(
                        hasContent = response?.data != null && hasContent(response.data),
                        hasNext = false
                    )
                }
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                Timber.e(ex)
            } finally {
                _taskMutex.unlock() // 释放锁
            }
        }
    }

    open fun hasContent(valueT: ValueT): Boolean {
        return true
    }
}