package ceui.pixiv.websocket

import kotlinx.coroutines.flow.Flow

/**
 * Callback interface for [WebSocketEvent]s with no-op defaults — override
 * only the lifecycle events you care about, ignore the rest.
 *
 * Modelled after [DefaultLifecycleObserver][androidx.lifecycle.DefaultLifecycleObserver]:
 * every method has an empty default body so a consumer that only cares about
 * connection open / close can write:
 *
 * ```kotlin
 * client.events.collect(object : WebSocketEventListener {
 *     override fun onOpen() { showConnectedBadge() }
 *     override fun onReconnectScheduled(attempt: Int, delayMillis: Long) {
 *         showReconnectingBadge(attempt)
 *     }
 * })
 * ```
 *
 * This interface deliberately does **not** have `onText` / `onBinary`
 * methods. Received business messages are delivered on
 * [WebSocketClient.incoming] as [IncomingMessage] — keeping them off this
 * listener prevents the "I subscribed to events and thought I'd handle
 * messages there" class of bug.
 */
interface WebSocketEventListener {
    fun onOpen() {}
    fun onClosing(code: Int, reason: String) {}
    fun onClosed(code: Int, reason: String) {}
    fun onFailure(throwable: Throwable, responseCode: Int?) {}
    fun onReconnectScheduled(attempt: Int, delayMillis: Long) {}
}

/**
 * Collect this [Flow] of [WebSocketEvent]s and dispatch each event to the
 * corresponding method on [listener]. Suspends until the flow completes or
 * the coroutine is cancelled — same semantics as [Flow.collect].
 *
 * ```kotlin
 * scope.launch {
 *     client.events.collect(object : WebSocketEventListener {
 *         override fun onOpen() { … }
 *     })
 * }
 * ```
 *
 * For incoming text / binary frames, collect [WebSocketClient.incoming]
 * directly — there is no listener adapter for [IncomingMessage] because
 * the type itself is small enough that a `when`-expression at the call site
 * is both shorter and clearer than a callback object.
 */
suspend fun Flow<WebSocketEvent>.collect(listener: WebSocketEventListener) {
    collect { event ->
        when (event) {
            is WebSocketEvent.Open -> listener.onOpen()
            is WebSocketEvent.Closing -> listener.onClosing(event.code, event.reason)
            is WebSocketEvent.Closed -> listener.onClosed(event.code, event.reason)
            is WebSocketEvent.Failure -> listener.onFailure(event.throwable, event.responseCode)
            is WebSocketEvent.ReconnectScheduled -> listener.onReconnectScheduled(event.attempt, event.delayMillis)
        }
    }
}
