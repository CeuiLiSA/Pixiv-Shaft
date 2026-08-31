package ceui.pixiv.websocket

/**
 * Why the current connection cycle is being retried. Built by
 * [RobustWebSocketClient] from each `onClosed` / `onFailure` callback and
 * passed in two directions:
 *
 *  - **Down to [ReconnectStrategy.nextDelayMillis]** so the strategy can
 *    decide whether to retry at all (fatal codes → return `null` to give up)
 *    and how long to wait. The default [ExponentialBackoffWithJitter]
 *    short-circuits on the obviously-fatal codes — see
 *    [ExponentialBackoffWithJitter.DEFAULT_SHOULD_RECONNECT].
 *  - **Up to [WebSocketState.Reconnecting.lastFailure]** so UI code can show
 *    a structured reason ("token expired, please re-login" vs "server
 *    unreachable, retrying…") instead of a flat string.
 *
 * The two variants mirror the two ways a WebSocket connection can end:
 * a graceful Close frame from the peer, or an abrupt failure (network error,
 * handshake rejection, exception). They are not interchangeable — picking the
 * right one is what lets the strategy distinguish "server kicked me out
 * politely with code 1008" from "TCP RST midway through".
 */
sealed class FailureContext {

    /** Short human-readable description for logging and fallback UI display. */
    abstract val message: String

    /**
     * Peer sent a WebSocket Close frame and the handshake completed.
     *
     * @property code   RFC 6455 close code. Notable buckets:
     *  - 1000 — normal closure (server restart, idle timeout) — usually transient
     *  - 1001 — going away (page navigation, server shutdown) — usually transient
     *  - 1002, 1003, 1007, 1008, 1009, 1010 — protocol/policy errors — usually fatal
     *  - 1011 — server internal error — usually transient
     *  - 1012, 1013, 1014 — service restart, try again later, bad gateway — transient
     *  - 4000-4999 — application-defined; semantics depend on your protocol
     * @property reason short UTF-8 reason string the peer included in the Close frame
     */
    data class Closed(val code: Int, val reason: String) : FailureContext() {
        override val message: String
            get() = if (reason.isEmpty()) "closed: $code" else "closed: $code $reason"
    }

    /**
     * Low-level failure: TCP/TLS error, DNS lookup failure, handshake rejected
     * by HTTP status, or any exception thrown from OkHttp's listener plumbing.
     *
     * @property throwable the exception OkHttp surfaced
     * @property httpCode  HTTP status code from the upgrade response, if any.
     *                     Non-null for handshake rejections (e.g. 401, 403,
     *                     404, 429, 503); null for pre-upgrade failures
     *                     (DNS, connect, TLS handshake)
     */
    data class Failure(val throwable: Throwable, val httpCode: Int?) : FailureContext() {
        override val message: String
            get() {
                val cause = throwable.message ?: throwable::class.simpleName ?: "unknown"
                return if (httpCode != null) "HTTP $httpCode: $cause" else cause
            }
    }
}
