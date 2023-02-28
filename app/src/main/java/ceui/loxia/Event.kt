package ceui.loxia

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData

// https://medium.com/androiddevelopers/livedata-with-snackbar-navigation-and-other-events-the-singleliveevent-case-ac2622673150
open class Event<out T>(private val content: T, private val expireMillis: Long = 0L) {

    var hasBeenHandled = false
        private set // Allow external read but not write

    private val dispatchTime = System.currentTimeMillis()

    /**
     * Returns the content and prevents its use again.
     */
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else if (expireMillis > 0 && expireMillis < (System.currentTimeMillis() - dispatchTime)) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    /**
     * Returns the content, even if it's already been handled.
     */
    fun peekContent(): T = content
}


inline fun <T> LiveData<Event<T>>.observeEvent(owner: LifecycleOwner, crossinline onEventUnhandledContent: (T) -> Unit) {
    observe(owner, { it?.getContentIfNotHandled()?.let(onEventUnhandledContent) })
}