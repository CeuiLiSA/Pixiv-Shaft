package ceui.pixiv.websocket

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okio.ByteString

/**
 * Contract for a WebSocket client that supports connect/disconnect lifecycle,
 * send operations, and observable state/events.
 *
 * The production implementation is [RobustWebSocketClient], which adds
 * auto-reconnect, auth refresh, backpressure, and stale-callback guards
 * on top of OkHttp.
 *
 * Depend on this interface (not the concrete class) in app-level code
 * so that tests can substitute a lightweight fake.
 */
interface WebSocketClient {

    /** Current connection state. */
    val state: StateFlow<WebSocketState>

    /**
     * **Lifecycle observability** events — `Open`, `Closing`, `Closed`,
     * `Failure`, `ReconnectScheduled`. Best-effort, `DROP_OLDEST` on overflow.
     *
     * Received business messages are **not** on this flow; subscribe to
     * [incoming] for those.
     */
    val events: SharedFlow<WebSocketEvent>

    /**
     * Inbound business messages as [IncomingMessage] values (text or binary).
     *
     * Kept separate from [events] so that a consumer writing
     * `client.events.collect { ... }` cannot silently miss received frames.
     * Larger buffer and `SUSPEND`-on-overflow semantics favour correctness
     * over fire-and-forget, but delivery is still best-effort — if a
     * consumer parks forever, the emitter will eventually drop frames
     * rather than block the OkHttp dispatcher. For guaranteed delivery,
     * use application-level acknowledgements.
     */
    val incoming: SharedFlow<IncomingMessage>

    /**
     * Open the connection. Idempotent: if the client is already active,
     * this is a no-op.
     */
    fun connect()

    /**
     * Close the connection gracefully by sending a Close frame. Auto-reconnect
     * is suppressed until [connect] is called again.
     *
     * @param code   WebSocket close code (1000 = normal). See RFC 6455 §7.4.
     * @param reason short human-readable explanation; max 123 bytes UTF-8.
     */
    fun disconnect(code: Int = 1000, reason: String = "client disconnect")

    /**
     * **Debug / test hook** — abruptly tear down the underlying socket
     * *without* sending a close frame, as if the network had gone away.
     * Unlike [disconnect], this is **not** a stop request: the listener's
     * `onFailure` will fire and the configured reconnect strategy will
     * schedule a retry.
     *
     * Legitimate uses:
     *  - Debug screens demonstrating reconnect behaviour.
     *  - Tests that need a deterministic way to trigger the failure path
     *    without actually killing TCP.
     *
     * **Do not** use this as a product "force reconnect" feature: it
     * (a) bypasses the coordinator's attempt counter, and (b) tempts callers
     * into reaching past session-scoped managers like
     * [WebSocketManager] and breaking their
     * invariants. If you need "force disconnect + auto reconnect" as a
     * product behaviour, add a clearly-named method here instead.
     *
     * **Note:** if [disconnect] was called previously without a subsequent
     * [connect], the client is in stop state and [cancel] is a no-op (there
     * is no socket to cancel). Call [connect] first if you want to force a
     * reconnect after a disconnect.
     */
    fun cancel()

    /**
     * Permanent teardown — release all resources. The instance cannot be
     * reused after this call.
     */
    fun close()

    /**
     * Send a text frame. Returns `true` if the frame was accepted into the
     * outgoing buffer, `false` if the client is closed/stopped or the buffer
     * rejected it.
     */
    fun send(text: String): Boolean

    /**
     * Send a binary frame. Same semantics as [send] (text).
     */
    fun send(bytes: ByteString): Boolean

    /**
     * Suspending send — applies real backpressure to a coroutine-based
     * producer. Suspends until the outgoing buffer has room.
     *
     * Returns `true` if the frame was enqueued; `false` if the client is
     * closed or stopped (either before the call, or while the caller was
     * suspended waiting for room). Uses the same `Boolean` convention as
     * [send] so callers can treat the two the same way — no exception path
     * to special-case.
     */
    suspend fun sendSuspending(text: String): Boolean

    /** Suspending binary send. See [sendSuspending] (text). */
    suspend fun sendSuspending(bytes: ByteString): Boolean
}
