package ceui.loxia

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

inline fun<reified InterfaceT> Fragment.sendAction(action: (receiver: InterfaceT)->Boolean) {
    var received = false
    var itr: Fragment? = this
    while (itr != null) {
        val receiver = itr as? InterfaceT
        if (receiver != null  && action(receiver)) {
            received = true
            break
        } else {
            itr = itr.parentFragment
        }
    }

    if (!received) {
        val receiver = this.activity as? InterfaceT
        if (receiver != null) {
            action(receiver)
        }
    }
}

inline fun<reified InterfaceT> View.sendAction(action: (receiver: InterfaceT)->Boolean) {
    val fragment = this.findFragment<Fragment>()
    fragment.sendAction<InterfaceT>(action)
}

fun Fragment.launchSuspend(block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
        try {
            block()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}

fun Fragment.launchSuspend(sender: ProgressTextButton, block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
        try {
            sender.showProgress()
            block()
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            sender.hideProgress()
        }
    }
}

fun Fragment.launchSuspend(sender: ProgressImageButton, block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
        try {
            sender.showProgress(true)
            block()
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            sender.showProgress(false)
        }
    }
}

fun NavOptions.Builder.setHorizontalSlide(): NavOptions.Builder {
    return setEnterAnim(R.anim.h_slide_enter)
        .setExitAnim(R.anim.h_slide_exit)
        .setPopEnterAnim(R.anim.h_slide_popenter)
        .setPopExitAnim(R.anim.h_slide_popexit)
}

fun NavOptions.Builder.setVerticalSlide(): NavOptions.Builder {
    return setEnterAnim(R.anim.v_slide_enter)
        .setExitAnim(R.anim.v_slide_exit)
        .setPopEnterAnim(R.anim.v_slide_popenter)
        .setPopExitAnim(R.anim.v_slide_popexit)
}


fun NavOptions.Builder.setFadeIn(): NavOptions.Builder {
    return setEnterAnim(R.anim.slow_fade_in)
        .setExitAnim(R.anim.slow_fade_out)
        .setPopEnterAnim(R.anim.slow_fade_in)
        .setPopExitAnim(R.anim.slow_fade_out)
}


fun Fragment.pushFragment(id: Int, bundle: Bundle? = null) {
    findNavController().navigate(
        id,
        bundle,
        NavOptions.Builder().setHorizontalSlide().build()
    )
}

fun Fragment.fadeInFragment(id: Int, bundle: Bundle? = null) {
    findNavController().navigate(
        id,
        bundle,
        NavOptions.Builder().setFadeIn().build()
    )
}

inline fun <reified ActionReceiverT> Fragment.findActionReceiverOrNull(): ActionReceiverT? {
    var itr: Fragment? = this
    while (itr != null) {
        val receiver = itr as? ActionReceiverT
        if (receiver != null) {
            return receiver
        } else {
            itr = itr.parentFragment
        }
    }

    return activity as? ActionReceiverT
}


inline fun<reified ActionReceiverT> View.findActionReceiverOrNull(): ActionReceiverT? {
    val fragment = this.findFragmentOrNull<Fragment>()
    return fragment?.findActionReceiverOrNull<ActionReceiverT>()
}

inline fun<reified ActionReceiverT> View.findActionReceiver(): ActionReceiverT {
    val fragment = this.findFragment<Fragment>()
    return fragment.findActionReceiverOrNull<ActionReceiverT>()!!
}

inline fun <reified F : Fragment> View.findFragmentOrNull(): F? {
    return try {
        val targetFragment = findFragment<Fragment>()
        if (targetFragment is F) {
            targetFragment as F
        } else null
    } catch (e: Exception) {
        null
    }
}