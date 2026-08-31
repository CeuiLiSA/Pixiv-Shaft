package ceui.pixiv.websocket

/**
 * **Lifecycle observability** events emitted by [WebSocketClient.events].
 *
 * These map 1:1 to OkHttp's WebSocketListener lifecycle callbacks plus a
 * synthesized [ReconnectScheduled] for observability into the auto-reconnect
 * path. They are deliberately **not** where incoming business messages live —
 * received text / binary frames are delivered on [WebSocketClient.incoming]
 * as [IncomingMessage] instances so the two concerns cannot be conflated
 * at the call site.
 *
 * The events flow uses `BufferOverflow.DROP_OLDEST` semantics — events are
 * **observability**, not delivery-guaranteed. Slow consumers will lose the
 * oldest events. That's acceptable here because "the UI missed one `Open`
 * event" is harmless; the current state is still available via
 * [WebSocketClient.state].
 */
sealed class WebSocketEvent {

    /** Handshake completed; the socket is open. */
    data object Open : WebSocketEvent()

    /**
     * Server is initiating a graceful close (sent a Close frame). The client
     * has already echoed the close back automatically by the time this fires.
     */
    data class Closing(val code: Int, val reason: String) : WebSocketEvent()

    /** Connection fully closed (both peers ack'd the close). */
    data class Closed(val code: Int, val reason: String) : WebSocketEvent()

    /**
     * The connection failed unexpectedly (network error, abrupt close, etc).
     * If auto-reconnect is enabled this will typically be followed by
     * [ReconnectScheduled] then [Open].
     */
    data class Failure(val throwable: Throwable, val responseCode: Int?) : WebSocketEvent()

    /**
     * Auto-reconnect scheduled the next attempt. Useful for surfacing
     * "reconnecting in N seconds" UI without polling [WebSocketClient.state].
     */
    data class ReconnectScheduled(val attempt: Int, val delayMillis: Long) : WebSocketEvent()
}
