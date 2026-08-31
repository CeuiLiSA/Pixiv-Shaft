package ceui.pixiv.websocket

/**
 * High-level connection state of a [RobustWebSocketClient].
 *
 * State transitions (driven internally by the client — not callable by users):
 *
 * ```
 *                ┌──────┐  connect()  ┌────────────┐  onOpen   ┌───────────┐
 *                │ Idle ├────────────►│ Connecting ├──────────►│ Connected │
 *                └──────┘             └─────┬──────┘           └─────┬─────┘
 *                                           │ onFailure              │
 *                                           │ / onClosed             │ onFailure
 *                                           ▼                        │ / onClosed
 *                                  ┌──────────────┐                  │
 *                                  │ Reconnecting │◄─────────────────┘
 *                                  └──────┬───────┘
 *                                         │ disconnect() / strategy exhausted
 *                                         ▼
 *                                ┌──────────────┐
 *                                │ Disconnected │  (terminal until connect() called again)
 *                                └──────────────┘
 * ```
 *
 * `Disconnected` is the terminal state for a *user-initiated* shutdown — the
 * client will not auto-reconnect from `Disconnected`. Server-initiated closes
 * and network failures land in `Reconnecting`, where the configured
 * [ReconnectStrategy] decides what happens next.
 *
 * There is no separate `Disconnecting` state: the close-frame handshake is
 * fast and the client transitions atomically from `Connected` (or
 * `Reconnecting`) to `Disconnected` from the caller's perspective. Late
 * server-side close acknowledgements are reported via [WebSocketEvent.Closed]
 * but do not produce a transient state.
 */
sealed class WebSocketState {

    /** Client has been constructed but [RobustWebSocketClient.connect] has not been called yet. */
    data object Idle : WebSocketState()

    /** Handshake in progress. */
    data object Connecting : WebSocketState()

    /**
     * Socket open and ready to send/receive.
     *
     * @property sinceMillis system uptime millis when the connection was established
     *                       (not wall-clock — use for "uptime since open" calculations)
     */
    data class Connected(val sinceMillis: Long) : WebSocketState()

    /**
     * Connection lost; waiting [delayMillis] before attempting reconnect attempt [attempt].
     *
     * @property attempt           1-based attempt number
     * @property delayMillis       configured backoff for this attempt
     * @property nextAttemptAtMillis system uptime millis when the retry will fire
     * @property lastFailure       structured reason the previous connection
     *                             ended; UI can pattern-match on
     *                             [FailureContext.Closed] vs
     *                             [FailureContext.Failure] to show specific
     *                             messages ("token expired" vs "server
     *                             unreachable") instead of a flat string
     */
    data class Reconnecting(
        val attempt: Int,
        val delayMillis: Long,
        val nextAttemptAtMillis: Long,
        val lastFailure: FailureContext,
    ) : WebSocketState()

    /**
     * Terminal state. Reached either via a user-initiated
     * [RobustWebSocketClient.disconnect] / [RobustWebSocketClient.close], or
     * because the configured [ReconnectStrategy] gave up.
     */
    data object Disconnected : WebSocketState()

    val isConnected: Boolean get() = this is Connected
    val isActive: Boolean get() = this is Connecting || this is Connected || this is Reconnecting
}
