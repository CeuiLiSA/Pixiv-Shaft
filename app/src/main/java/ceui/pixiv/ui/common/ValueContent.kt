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
import kotlinx.coroutines.withContext
import timber.log.Timber

open class ValueContent<ValueT>(
    private val coroutineScope: CoroutineScope,
    private val dataFetcher: suspend (hint: RefreshHint) -> ValueT,
    private val responseStore: ResponseStore<ValueT>? = null,
) : RefreshOwner {

    private val _result = MutableLiveData<ValueT>()
    val result: LiveData<ValueT> = _result

    private val _refreshState = MutableLiveData<RefreshState>()
    override val refreshState: LiveData<RefreshState> = _refreshState

    override fun refresh(hint: RefreshHint) {
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

                if (hint == RefreshHint.PullToRefresh || responseStore == null || responseStore.isCacheExpired()) {
                    val response = withContext(Dispatchers.IO) {
                        dataFetcher(hint).also {
                            responseStore?.writeToCache(it)
                        }
                    }
                    applyResult(response)
                }

                _refreshState.value = RefreshState.LOADED(
                    hasContent = true,
                    hasNext = false
                )
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                Timber.e(ex)
            }
        }
    }

    open fun applyResult(valueT: ValueT) {
        _result.value = valueT
    }
}