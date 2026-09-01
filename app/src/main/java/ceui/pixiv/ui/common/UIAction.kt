package ceui.pixiv.ui.common

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.lifecycle.lifecycleScope
import ceui.pixiv.widgets.ProgressIndicator
import ceui.pixiv.widgets.alertYesOrCancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

fun Fragment.launchSuspend(block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
        try {
            block()
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            context?.let {
                alertYesOrCancel(ex.getHumanReadableMessage(it))
            }
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
            if (ex is CancellationException) throw ex
            context?.let {
                alertYesOrCancel(ex.getHumanReadableMessage(it))
            }
            Timber.e(ex)
        } finally {
            sender.hideProgress()
        }
    }
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

