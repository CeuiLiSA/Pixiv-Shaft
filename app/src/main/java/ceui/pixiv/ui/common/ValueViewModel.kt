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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

fun <T> Fragment.pixivValueViewModel(
    dataFetcher: suspend (hint: RefreshHint) -> T,
    responseStore: ResponseStore<T>? = null,
): Lazy<ValueViewModel<T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(dataFetcher, responseStore) as T
            }
        }
    }
}

inline fun <ArgsT, T> Fragment.pixivValueViewModel(
    noinline argsProducer: () -> ArgsT,
    responseStore: ResponseStore<T>? = null,
    noinline dataFetcher: suspend (hint: RefreshHint, ArgsT) -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val args = argsProducer()
                return ValueViewModel(dataFetcher = { hint ->
                    dataFetcher(hint, args)
                }, responseStore) as T
            }
        }
    }
}

inline fun <T> Fragment.pixivValueViewModel(
    noinline ownerProducer: () -> ViewModelStoreOwner = { this },
    responseStore: ResponseStore<T>? = null,
    noinline dataFetcher: suspend (hint: RefreshHint) -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels(ownerProducer = ownerProducer) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(dataFetcher, responseStore) as T
            }
        }
    }
}


class ValueViewModel<T>(
    private val dataFetcher: suspend (hint: RefreshHint) -> T,
    private val responseStore: ResponseStore<T>? = null,
) : ViewModel(), RefreshOwner {

    private val valueContent = ValueContent(viewModelScope, dataFetcher, responseStore)

    override val refreshState: LiveData<RefreshState>
        get() = valueContent.refreshState

    val result: LiveData<T> get() = valueContent.result

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override fun refresh(hint: RefreshHint) {
        valueContent.refresh(hint)
    }
}