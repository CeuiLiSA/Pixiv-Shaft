package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber

open class ValueContent<ValueT>(
    private val coroutineScope: CoroutineScope,
    private val dataFetcher: suspend () -> ValueT,
    private val responseStore: ResponseStore<ValueT>? = null,
) : RefreshOwner {

    private val _result = MutableLiveData<ValueT>()
    val result: LiveData<ValueT> = _result

    private val _refreshState = MutableLiveData<RefreshState>()
    override val refreshState: LiveData<RefreshState> = _refreshState

    private val _taskMutex = Mutex() // 互斥锁，防止重复刷新

    override fun refresh(hint: RefreshHint) {
        if (!_taskMutex.tryLock()) {
            Timber.e("ValueContent refresh tryLock returned")
            return // 如果当前已有刷新任务在执行，则直接返回
        }

        Timber.e("ValueContent refresh tryLock passed")
        coroutineScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                if (hint == RefreshHint.ErrorRetry) {
                    delay(300L)
                }

                if (hint == RefreshHint.InitialLoad) {
                    responseStore?.loadFromCache()?.let { storedResponse ->
                        applyResult(storedResponse)
                    }
                }

                val response = if (hint == RefreshHint.PullToRefresh || responseStore == null || responseStore.isCacheExpired()) {
                    val ret = withContext(Dispatchers.IO) {
                        dataFetcher().also {
                            responseStore?.writeToCache(it)
                        }
                    }
                    applyResult(ret)
                    ret
                } else {
                    null
                }

                _refreshState.value = RefreshState.LOADED(
                    hasContent = response != null && hasContent(response),
                    hasNext = false
                )
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

    open fun applyResult(valueT: ValueT) {
        _result.value = valueT
    }
}