package ceui.pixiv.ui.common

import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.fragment.app.createViewModelLazy
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner

@MainThread
inline fun <reified VM : ViewModel, ArgT1 : Any> Fragment.constructVM(
    crossinline arg1Producer: () -> ArgT1,
    noinline vmCtr: (ArgT1) -> VM
) = createViewModelLazy(VM::class, { this.viewModelStore }) {
    val frag = this
    object : AbstractSavedStateViewModelFactory(frag, null) {
        val arg1 = arg1Producer()

        override fun <T : ViewModel> create(
            key: String,
            modelClass: Class<T>,
            handle: SavedStateHandle
        ): T {
            return vmCtr(arg1) as T
        }
    }
}

@MainThread
inline fun <reified VM : ViewModel, ArgT1 : Any> Fragment.constructKeyedVM(
    noinline keyPrefixProvider: () -> String,
    crossinline arg1Producer: () -> ArgT1,
    noinline ownerProducer: () -> ViewModelStoreOwner = { this },
    noinline savedStateProducer: () -> SavedStateRegistryOwner = { this },
    noinline vmCtr: (ArgT1) -> VM,
) = createKeyedViewModelLazy(keyPrefixProvider, VM::class, { ownerProducer().viewModelStore }) {
    object : AbstractSavedStateViewModelFactory(savedStateProducer(), null) {
        val arg1 = arg1Producer()

        override fun <T : ViewModel> create(
            key: String,
            modelClass: Class<T>,
            handle: SavedStateHandle
        ): T {
            return vmCtr(arg1) as T
        }
    }
}