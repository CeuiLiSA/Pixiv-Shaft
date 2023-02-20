package ceui.refactor

import android.animation.AnimatorInflater
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import ceui.lisa.R

fun <T: View> T.setOnClick(listener: (T) -> Unit) {
    stateListAnimator =
        AnimatorInflater.loadStateListAnimator(context, R.animator.button_press_alpha)
    setOnClickListener {
        listener(this)
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

inline fun<reified ActionReceiverT> Fragment.findActionReceiverOrNull(): ActionReceiverT? {
    var itr: Fragment? = this
    while (itr != null) {
        val receiver = itr as? ActionReceiverT
        if (receiver != null) {
            return receiver
        } else {
            itr = itr.parentFragment
        }
    }

    val receiver = this.activity as? ActionReceiverT
    if (receiver != null) {
        return receiver
    } else {
        return null
    }
}


inline fun<reified ActionReceiverT> View.findActionReceiverOrNull(): ActionReceiverT? {
    val fragment = this.findFragmentOrNull<Fragment>()
    return fragment?.findActionReceiverOrNull<ActionReceiverT>()
}

inline fun<reified ActionReceiverT> View.findActionReceiver(): ActionReceiverT {
    val fragment = this.findFragment<Fragment>()
    return fragment.findActionReceiverOrNull<ActionReceiverT>()!!
}