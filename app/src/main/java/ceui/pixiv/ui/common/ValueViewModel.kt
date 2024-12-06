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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

fun <T> Fragment.pixivValueViewModel(
    loader: suspend () -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(loader) as T
            }
        }
    }
}

inline fun <ArgsT, T> Fragment.pixivValueViewModel(
    noinline argsProducer: () -> ArgsT,
    noinline loader: suspend (ArgsT) -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val args = argsProducer()
                return ValueViewModel(loader = {
                    loader(args)
                }) as T
            }
        }
    }
}

inline fun <T> Fragment.pixivValueViewModel(
    noinline ownerProducer: () -> ViewModelStoreOwner = { this },
    noinline loader: suspend () -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels(ownerProducer = ownerProducer) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(loader) as T
            }
        }
    }
}


class ValueViewModel<T>(
    private val loader: suspend () -> T,
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

               val response = loader()
                _result.value = response
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