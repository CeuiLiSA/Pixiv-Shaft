package ceui.loxia

import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.findFragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.FragmentResultRequestIdOwner
import ceui.pixiv.widgets.FragmentResultByFragment
import ceui.pixiv.widgets.FragmentResultStore
import ceui.pixiv.widgets.PixivDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

inline fun <reified InterfaceT> Fragment.sendAction(action: (receiver: InterfaceT) -> Boolean) {
    var received = false
    var itr: Fragment? = this
    while (itr != null) {
        val receiver = itr as? InterfaceT
        if (receiver != null && action(receiver)) {
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

inline fun <reified InterfaceT> View.sendAction(action: (receiver: InterfaceT) -> Boolean) {
    val fragment = this.findFragment<Fragment>()
    fragment.sendAction<InterfaceT>(action)
}

fun Fragment.launchSuspend(block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
        try {
            block()
        } catch (ex: Exception) {
            Timber.e(ex)
        }
    }
}

fun Fragment.launchSuspend(sender: ProgressIndicator, block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
        try {
            sender.showProgress()
            block()
        } catch (ex: Exception) {
            Timber.e(ex)
        } finally {
            sender.hideProgress()
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

const val FRAGMENT_RESULT_REQUEST_ID = "FragmentResultRequestId"


internal fun<T> T.listenToResultStore(resultStore: FragmentResultStore) where T: Fragment, T: FragmentResultRequestIdOwner {
    val fragment = this
    val uniqueId = fragmentUniqueId
    viewLifecycleOwner.lifecycleScope.launchWhenResumed {
        resultStore.getTypedResult(uniqueId)?.let { result ->
            resultStore.getTypedTask(uniqueId)?.let { task ->
                task.complete(FragmentResultByFragment(result, fragment))
                resultStore.removeResult(uniqueId)
            }
        }
    }
}

inline fun <FragmentT, reified T> FragmentT.pushFragmentForResult(
    id: Int,
    bundle: Bundle? = null,
    crossinline onResult: FragmentT.(T) -> Unit
) where FragmentT : Fragment, FragmentT : FragmentResultRequestIdOwner {
    val caller = this
    Common.showLog("dsaasdw gogogogo ${caller.fragmentUniqueId} picked launchWhenResumed use ${lifecycle.currentState}")

    val fragmentResultStore by activityViewModels<FragmentResultStore>()
    val requestId = fragmentUniqueId
    val task = CompletableDeferred<FragmentResultByFragment<FragmentT, T>>()
    fragmentResultStore.putTask(requestId, task as CompletableDeferred<FragmentResultByFragment<*, *>>)

    // 如果 bundle 为 null，则创建一个新的 Bundle
    val args = bundle ?: Bundle()
    // 将 requestId 添加到 Bundle 中
    args.putString(FRAGMENT_RESULT_REQUEST_ID, requestId)

    MainScope().launch {
        val result = task.await()
        result.fragment.onResult(result.result)
    }
    findNavController().navigate(
        id,
        args,
        NavOptions.Builder().setHorizontalSlide().build()
    )
}


inline fun <FragmentT, reified T> FragmentT.promptFragmentForResult(
    dialogProducer: () -> DialogFragment,
    bundle: Bundle? = null,
    crossinline onResult: FragmentT.(T) -> Unit
) where FragmentT : Fragment, FragmentT : FragmentResultRequestIdOwner {
    val caller = this
    Common.showLog("dsaasdw gogogogo ${caller.fragmentUniqueId} picked launchWhenResumed use ${lifecycle.currentState}")

    val fragmentResultStore by activityViewModels<FragmentResultStore>()
    val requestId = fragmentUniqueId
    val task = CompletableDeferred<FragmentResultByFragment<FragmentT, T>>()
    fragmentResultStore.putTask(requestId, task as CompletableDeferred<FragmentResultByFragment<*, *>>)
    fragmentResultStore.registerListener(requestId, lifecycle)
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            super.onDestroy(owner)
            fragmentResultStore.unRegisterListener(requestId)
        }
    })

    // 如果 bundle 为 null，则创建一个新的 Bundle
    val args = bundle ?: Bundle()
    // 将 requestId 添加到 Bundle 中
    args.putString(FRAGMENT_RESULT_REQUEST_ID, requestId)

    MainScope().launch {
        val result = task.await()
        result.fragment.onResult(result.result)
    }
    val dialogFragment = dialogProducer()
    dialogFragment.apply {
        arguments = args
    }
    dialogFragment.show(childFragmentManager, "Tag")
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


inline fun <reified ActionReceiverT> View.findActionReceiverOrNull(): ActionReceiverT? {
    val fragment = this.findFragmentOrNull<Fragment>()
    return fragment?.findActionReceiverOrNull<ActionReceiverT>()
}

inline fun <reified ActionReceiverT> View.findActionReceiver(): ActionReceiverT {
    val fragment = this.findFragment<Fragment>()
    return fragment.findActionReceiverOrNull<ActionReceiverT>()!!
}

fun <T : RecyclerView> T.clearItemDecorations() {
    while (itemDecorationCount > 0) {
        removeItemDecorationAt(0)
    }
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

inline fun <reified T : Fragment> Fragment.findAncestorOrSelf(): T? {
    if (this is T) {
        return this
    } else {
        return findAncestor()
    }
}
