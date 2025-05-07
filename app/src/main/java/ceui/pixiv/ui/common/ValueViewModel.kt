package ceui.pixiv.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.keyedViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.reflect.KClass

fun <T> Fragment.pixivValueViewModel(
    dataFetcher: suspend () -> T,
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
    noinline dataFetcher: suspend (ArgsT) -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val args = argsProducer()
                return ValueViewModel(dataFetcher = {
                    dataFetcher(args)
                }, responseStore) as T
            }
        }
    }
}

inline fun <T> Fragment.pixivValueViewModel(
    noinline ownerProducer: () -> ViewModelStoreOwner = { this },
    responseStore: ResponseStore<T>? = null,
    noinline dataFetcher: suspend () -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels(ownerProducer = ownerProducer) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(dataFetcher, responseStore) as T
            }
        }
    }
}

inline fun <T> Fragment.pixivKeyedValueViewModel(
    keyPrefix: String,
    noinline ownerProducer: () -> ViewModelStoreOwner = { this },
    responseStore: ResponseStore<T>? = null,
    noinline dataFetcher: suspend () -> T,
): Lazy<ValueViewModel<T>> {
    return this.keyedViewModels(keyPrefixProvider = { keyPrefix }, ownerProducer = ownerProducer) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(dataFetcher, responseStore) as T
            }
        }
    }
}

inline fun <T> ComponentActivity.pixivValueViewModel(
    responseStore: ResponseStore<T>? = null,
    noinline dataFetcher: suspend () -> T,
): Lazy<ValueViewModel<T>> {
    return viewModels(keyPrefixProvider = { "aaa" }) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(dataFetcher, responseStore) as T
            }
        }
    }
}

@MainThread
fun <VM : ViewModel> ComponentActivity.createKeyedViewModelLazy(
    keyPrefixProvider: () -> String,
    viewModelClass: KClass<VM>,
    storeProducer: () -> ViewModelStore,
    factoryProducer: (() -> ViewModelProvider.Factory)? = null
): Lazy<VM> {
    val factoryPromise = factoryProducer ?: {
        defaultViewModelProviderFactory
    }
    return KeyedViewModelLazy(keyPrefixProvider, viewModelClass, storeProducer, factoryPromise)
}


@MainThread
inline fun <reified VM : ViewModel> ComponentActivity.viewModels(
    noinline keyPrefixProvider: () -> String,
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
) = createKeyedViewModelLazy(keyPrefixProvider, VM::class, { this.viewModelStore },
    factoryProducer ?: { this.defaultViewModelProviderFactory })



class ValueViewModel<T>(
    private val dataFetcher: suspend () -> T,
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