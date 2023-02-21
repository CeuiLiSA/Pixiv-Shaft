package ceui.loxia

import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import ceui.refactor.ListItemHolder
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

class SlinkyListViewModel<FragmentT: NavFragment>(
    private val _repository: Repository<FragmentT>
) : ViewModel() {

    val refreshState: LiveData<RefreshState> = _repository.refreshState
    val holderList: LiveData<List<ListItemHolder>> = _repository.holderList
    var isInitialLoaded = false

    fun refresh(refreshHint: RefreshHint, fragmentT: FragmentT) {
        viewModelScope.launch {
            _repository.refreshInvoker(fragmentT, refreshHint)
        }
    }

    fun loadMore(fragmentT: FragmentT) {
        viewModelScope.launch {
            _repository.loadMoreInvoker(fragmentT)
        }
    }
}


inline fun <FragmentT: NavFragment> FragmentT.slinkyListVM(
    noinline ownerProducer: (() -> ViewModelStoreOwner),
    noinline keyProducer: () -> String,
    crossinline repositoryFactory: () -> Repository<FragmentT>
): Lazy<SlinkyListViewModel<FragmentT>> {
    return this.slinkyViewModels(
        keyProducer = keyProducer,
        ownerProducer = ownerProducer
    ) {
        val repository = repositoryFactory.invoke()
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SlinkyListViewModel(repository) as T
            }
        }
    }
}

inline fun <FragmentT: NavFragment> FragmentT.slinkyListVMCustom(
    crossinline repositoryFactory: () -> Repository<FragmentT>
): Lazy<SlinkyListViewModel<FragmentT>> {
    return this.viewModels {
        val repository = repositoryFactory.invoke()
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SlinkyListViewModel(repository) as T
            }
        }
    }
}

@MainThread
inline fun <reified VM : ViewModel> Fragment.slinkyViewModels(
    noinline keyProducer: () -> String,
    noinline ownerProducer: () -> ViewModelStoreOwner = { this },
    noinline extrasProducer: (() -> CreationExtras)? = null,
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
): Lazy<VM> {
    val owner by lazy(LazyThreadSafetyMode.NONE) { ownerProducer() }
    return createSlinkyViewModelLazy(
        VM::class,
        { owner.viewModelStore },
        {
            extrasProducer?.invoke()
                ?: (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
                ?: CreationExtras.Empty
        },
        factoryProducer ?: {
            (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
                ?: defaultViewModelProviderFactory
        },
        keyProducer
    )
}

@MainThread
fun <VM : ViewModel> Fragment.createSlinkyViewModelLazy(
    viewModelClass: KClass<VM>,
    storeProducer: () -> ViewModelStore,
    extrasProducer: () -> CreationExtras = { defaultViewModelCreationExtras },
    factoryProducer: (() -> ViewModelProvider.Factory)? = null,
    keyProducer: () -> String
): Lazy<VM> {
    val factoryPromise = factoryProducer ?: {
        defaultViewModelProviderFactory
    }
    return SlinkyViewModelLazy(
        viewModelClass,
        storeProducer,
        factoryPromise,
        extrasProducer,
        keyProducer
    )
}

class SlinkyViewModelLazy<VM : ViewModel> @JvmOverloads constructor(
    private val viewModelClass: KClass<VM>,
    private val storeProducer: () -> ViewModelStore,
    private val factoryProducer: () -> ViewModelProvider.Factory,
    private val extrasProducer: () -> CreationExtras = { CreationExtras.Empty },
    private val keyProducer: () -> String
) : Lazy<VM> {
    private var cached: VM? = null

    override val value: VM
        get() {
            val viewModel = cached
            return if (viewModel == null) {
                val factory = factoryProducer()
                val store = storeProducer()
                ViewModelProvider(
                    store,
                    factory,
                    extrasProducer()
                )[keyProducer(), viewModelClass.java].also {
                    cached = it
                }
            } else {
                viewModel
            }
        }

    override fun isInitialized(): Boolean = cached != null
}


