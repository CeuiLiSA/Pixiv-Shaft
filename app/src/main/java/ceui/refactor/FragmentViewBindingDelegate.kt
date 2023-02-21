package ceui.refactor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.viewbinding.ViewBinding
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class FragmentViewBindingDelegate<T : ViewBinding>(
    fragment: Fragment,
    val viewBindingFactory: (View) -> T
) : ReadOnlyProperty<Fragment, T> {
    private var binding: T? = null

    init {
        fragment.addOnViewDestroyListener {
            binding = null
        }
    }

    override fun getValue(thisRef: Fragment, property: KProperty<*>): T {
        val thisView = thisRef.requireView()
        val binding = binding
        if (binding != null) {
            if (binding.root == thisView) {
                return binding
            } else {
            }
        }

        val lifecycle = thisRef.viewLifecycleOwner.lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            throw IllegalStateException("Should not attempt to get bindings when Fragment views are destroyed.")
        }

        return viewBindingFactory(thisView).also {
            this.binding = it
            (this.binding as? ViewDataBinding)?.lifecycleOwner = thisRef.viewLifecycleOwner
        }
    }
}


fun <T : ViewBinding> Fragment.viewBinding(viewBindingFactory: (View) -> T) =
    FragmentViewBindingDelegate(this, viewBindingFactory)

class FragmentChildViewBindingDelegate<T : ViewBinding>(
    fragment: Fragment,
    val viewBindingFactory: (LayoutInflater, ViewGroup, Boolean) -> T
) : ReadOnlyProperty<Fragment, T> {
    private var binding: T? = null

    init {
        fragment.addOnViewDestroyListener {
            binding = null
        }
    }

    override fun getValue(thisRef: Fragment, property: KProperty<*>): T {
        val thisView = thisRef.requireView()

        val binding = binding
        if (binding != null) {
            return binding
        }

        val lifecycle = thisRef.viewLifecycleOwner.lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            throw IllegalStateException("Should not attempt to get bindings when Fragment views are destroyed.")
        }

        return viewBindingFactory(thisRef.layoutInflater, thisView as ViewGroup, false).also {
            this.binding = it
            (this.binding as? ViewDataBinding)?.lifecycleOwner = thisRef.viewLifecycleOwner
        }
    }
}


fun <T : ViewBinding> Fragment.childViewBinding(viewBindingFactory: (LayoutInflater, ViewGroup, Boolean) -> T) =
    FragmentChildViewBindingDelegate(this, viewBindingFactory)

fun Fragment.addOnViewDestroyListener(listener: ()->Unit) {
    // https://medium.com/@Zhuinden/an-update-to-the-fragmentviewbindingdelegate-the-bug-weve-inherited-from-autoclearedvalue-7fc0a89fcae1
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        val viewLifecycleOwnerLiveDataObserver =
            Observer<LifecycleOwner?> {
                val viewLifecycleOwner = it ?: return@Observer

                viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        listener()
                    }
                })
            }

        override fun onCreate(owner: LifecycleOwner) {
            viewLifecycleOwnerLiveData.observeForever(viewLifecycleOwnerLiveDataObserver)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            viewLifecycleOwnerLiveData.removeObserver(viewLifecycleOwnerLiveDataObserver)
        }
    })
}