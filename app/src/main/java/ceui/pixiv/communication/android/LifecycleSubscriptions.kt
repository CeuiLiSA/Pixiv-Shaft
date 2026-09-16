package ceui.pixiv.communication.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.pixiv.communication.EventSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

public enum class SubscriberErrorPolicy { Stop, ContinueAndReport }

/**
 * Collect foreground notices on Main. Fragment callers MUST use viewLifecycleOwner. Stop means
 * this subscription ends permanently after an error, not merely until the next STARTED window.
 * Call once per View creation. Returned Job can be cancelled without affecting other subscriptions.
 */
public fun <E : Any> EventSource<E>.collectIn(
    owner: LifecycleOwner,
    minState: Lifecycle.State = Lifecycle.State.STARTED,
    errorPolicy: SubscriberErrorPolicy = SubscriberErrorPolicy.Stop,
    onError: (Exception) -> Unit,
    handler: suspend (E) -> Unit,
): Job = events.collectIn(owner, minState, errorPolicy, onError, handler)

/** Also usable with ViewModel StateFlow or a repository Flow. No buffer or dispatcher is added upstream. */
public fun <T> Flow<T>.collectIn(
    owner: LifecycleOwner,
    minState: Lifecycle.State = Lifecycle.State.STARTED,
    errorPolicy: SubscriberErrorPolicy = SubscriberErrorPolicy.Stop,
    onError: (Exception) -> Unit,
    handler: suspend (T) -> Unit,
): Job {
    require(minState == Lifecycle.State.STARTED || minState == Lifecycle.State.RESUMED) {
        "UI subscriptions require STARTED or RESUMED"
    }
    // lifecycleScope has a SupervisorJob. Catch expected consumer failures inside this child;
    // never attach a standalone Job that could outlive the owner.
    return owner.lifecycleScope.launch(Dispatchers.Main.immediate) {
        val subscription = this
        fun report(error: Exception) {
            try {
                onError(error)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Failure reporting cannot kill sibling UI subscriptions.
            }
        }
        try {
            owner.repeatOnLifecycle(minState) {
                try {
                    this@collectIn.collect { value ->
                        // Synchronous sources (e.g. flowOf) need not check cancellation between
                        // emissions. A preceding handler may have stopped/destroyed this View.
                        currentCoroutineContext().ensureActive()
                        try {
                            handler(value)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            report(error)
                            if (errorPolicy == SubscriberErrorPolicy.Stop) {
                                subscription.cancel()
                                throw CancellationException("Subscription stopped after handler failure", error)
                            }
                        }
                    }
                    // Cancellation during the final synchronous handler is a stopped lifecycle
                    // window, not normal source completion. Preserve the next STARTED window.
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    // STOP cancels this lifecycle window, which must be allowed to restart.
                    // A cancellation thrown by the source/handler itself ends the subscription;
                    // otherwise repeatOnLifecycle would silently restart it in a later window.
                    if (currentCoroutineContext().isActive) subscription.cancel(cancelled)
                    throw cancelled
                }
                // A completed/closed source must not re-register on a later lifecycle transition.
                subscription.cancel()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Upstream failure terminates observation even with ContinueAndReport: automatically
            // restarting an arbitrary flow could repeat side effects or spin on a persistent error.
            report(error)
        }
    }
}
