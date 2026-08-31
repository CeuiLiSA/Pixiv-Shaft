package ceui.pixiv.websocket

import java.util.concurrent.TimeUnit

/**
 * Pure-data configuration for a [RobustWebSocketClient].
 *
 * This class holds values only — URLs, sizes, strategies, static headers.
 * Behavioural collaborators (the auth provider, the connectivity observer)
 * are passed as separate [RobustWebSocketClient] constructor parameters so
 * that `data class` equality stays meaningful: two configs that differ only
 * in which `authProvider` instance they reference would otherwise be
 * reported as unequal even when the app considers them interchangeable.
 *
 * Defaults are tuned for a chat-style long-lived connection over LTE/WiFi:
 *  - 30 second client-side ping (aggressive enough to detect dead NATs but
 *    not so frequent that it eats radio battery)
 *  - exponential reconnect (1 → 30 seconds), retry forever
 *  - 256-message outgoing buffer with [BackpressureStrategy.DropOldest], so
 *    a brief disconnect doesn't lose data and a sustained burst doesn't
 *    consume unbounded memory
 *  - 1 MB wire-level high-water mark on OkHttp's internal write queue
 *
 * @property url                  the `wss://` (or `ws://`) endpoint
 * @property pingIntervalMillis   client → server ping interval; OkHttp will
 *                                fail the connection if no pong comes back
 *                                within the same interval. Set to `0` to
 *                                disable client-side pings entirely.
 * @property reconnectStrategy    delay-between-attempts policy. Use
 *                                [ReconnectStrategy.NoRetry] to disable.
 * @property outgoingBufferSize   max messages to hold in the outgoing buffer.
 *                                Combined with [backpressureStrategy] to
 *                                decide what happens on overflow.
 * @property backpressureStrategy what to do when the outgoing buffer is full.
 *                                See [BackpressureStrategy].
 * @property txQueueHighWaterMarkBytes  hard ceiling on OkHttp's internal
 *                                send queue depth in bytes. When OkHttp's
 *                                queue exceeds this, the consumer pauses
 *                                (polls every [txBackpressurePollIntervalMillis])
 *                                until it drains. Prevents runaway memory
 *                                growth even if the wire is slower than the
 *                                producer. Default 1 MB; raise it for
 *                                throughput-heavy use cases.
 * @property txBackpressurePollIntervalMillis  how often to re-check the
 *                                wire queue depth while waiting for it to
 *                                drain. Default 50 ms.
 * @property subProtocols         optional WebSocket sub-protocols offered
 *                                during handshake (`Sec-WebSocket-Protocol`)
 * @property headers              **static** extra HTTP headers added to the
 *                                upgrade request (e.g. `Origin`,
 *                                `User-Agent`). For credentials that may
 *                                refresh during the connection's lifetime,
 *                                pass a `WebSocketAuthProvider` to
 *                                [RobustWebSocketClient] instead — its
 *                                dynamic headers are merged on top of
 *                                [headers] and win on key conflict.
 * @property maxAuthRefreshAttempts  how many times the client will retry
 *                                an auth failure with a refreshed token
 *                                before giving up. Default `1` matches
 *                                the REST `TokenAuthenticator` behaviour:
 *                                refresh once, and if the very next
 *                                connect *also* gets an auth failure,
 *                                terminate. Only meaningful when a
 *                                `WebSocketAuthProvider` is wired up on
 *                                the client.
 * @property logPayloads          whether to dump full message bodies at DEBUG;
 *                                if `false`, only sizes are logged. Leave
 *                                `false` in production unless you're chasing
 *                                a specific bug — payloads can contain PII.
 */
data class WebSocketConfig(
    val url: String,
    val pingIntervalMillis: Long = TimeUnit.SECONDS.toMillis(30),
    val reconnectStrategy: ReconnectStrategy = ExponentialBackoffWithJitter(),
    val outgoingBufferSize: Int = 256,
    val backpressureStrategy: BackpressureStrategy = BackpressureStrategy.DropOldest,
    val txQueueHighWaterMarkBytes: Long = 1_000_000L,
    val txBackpressurePollIntervalMillis: Long = 50,
    val subProtocols: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val maxAuthRefreshAttempts: Int = 1,
    val logPayloads: Boolean = false,
) {
    init {
        require(url.startsWith("ws://") || url.startsWith("wss://")) {
            "url must be a ws:// or wss:// URL, was: $url"
        }
        require(pingIntervalMillis >= 0) { "pingIntervalMillis must be >= 0" }
        require(outgoingBufferSize >= 1) { "outgoingBufferSize must be >= 1" }
        require(txQueueHighWaterMarkBytes >= 1024) {
            "txQueueHighWaterMarkBytes must be >= 1024 (1 KB)"
        }
        require(txBackpressurePollIntervalMillis in 1..5_000) {
            "txBackpressurePollIntervalMillis must be in [1, 5000]"
        }
        require(maxAuthRefreshAttempts >= 0) {
            "maxAuthRefreshAttempts must be >= 0 (0 = never refresh, fail fast)"
        }
    }
}
