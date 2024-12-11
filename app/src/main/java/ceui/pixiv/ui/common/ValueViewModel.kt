package ceui.pixiv.ui.common

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.slinkyViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

fun <T> Fragment.pixivValueViewModel(
    loader: suspend (hint: RefreshHint) -> T,
    responseStore: ResponseStore<T>? = null,
): Lazy<ValueViewModel<T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(loader, responseStore) as T
            }
        }
    }
}

inline fun <ArgsT, T> Fragment.pixivValueViewModel(
    noinline argsProducer: () -> ArgsT,
    responseStore: ResponseStore<T>? = null,
    noinline loader: suspend (hint: RefreshHint, ArgsT) -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val args = argsProducer()
                return ValueViewModel(loader = { hint ->
                    loader(hint, args)
                }, responseStore) as T
            }
        }
    }
}

inline fun <T> Fragment.pixivValueViewModel(
    noinline ownerProducer: () -> ViewModelStoreOwner = { this },
    responseStore: ResponseStore<T>? = null,
    noinline loader: suspend (hint: RefreshHint) -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels(ownerProducer = ownerProducer) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(loader, responseStore) as T
            }
        }
    }
}


class ValueViewModel<T>(
    private val loader: suspend (hint: RefreshHint) -> T,
    private val responseStore: ResponseStore<T>? = null,
) : ViewModel(), RefreshOwner {

    private val _refreshState = MutableLiveData<RefreshState>()
    override val refreshState: LiveData<RefreshState> = _refreshState

    private val _result = MutableLiveData<T>()
    val result: LiveData<T> = _result

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                if (hint == RefreshHint.ErrorRetry) {
                    delay(300L)
                }

                if (hint == RefreshHint.InitialLoad) {
                    responseStore?.loadFromCache()?.let { storedResponse ->
                        _result.value = storedResponse
                    }
                }

                if (hint == RefreshHint.PullToRefresh || responseStore == null || responseStore.isCacheExpired()) {
                    val response = withContext(Dispatchers.IO) {
                        loader(hint).also {
                            responseStore?.writeToCache(it)
                        }
                    }
                    _result.value = response
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
}