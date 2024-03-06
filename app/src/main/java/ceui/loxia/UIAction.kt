package ceui.loxia

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
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