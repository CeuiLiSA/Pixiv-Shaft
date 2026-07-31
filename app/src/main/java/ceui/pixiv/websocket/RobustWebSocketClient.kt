package ceui.pixiv.websocket

import android.os.SystemClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber

/**
 * Production-grade WebSocket client built on OkHttp.
 *
 * ## Features
 *  - Single-instance, thread-safe state machine — see [WebSocketState].
 *  - Auto-reconnect with pluggable [ReconnectStrategy] (default: exponential
 *    backoff with jitter, retry forever, capped at 30 s).
 *  - Network-aware fast retry — when an optional [ConnectivityObserver] reports
 *    the network coming back, the current backoff delay is short-circuited and
 *    a reconnect fires immediately.
 *  - **Strict per-connection FIFO ordering** for outgoing messages: every
 *    `send()` goes through a single-consumer [Channel] worker, so messages
 *    are delivered to the wire in exactly the order they were enqueued, even
 *    across reconnects and concurrent producers.
 *  - **Configurable backpressure** ([BackpressureStrategy]) plus a
 *    **wire-level high-water mark** that pauses the consumer when OkHttp's
 *    internal write queue exceeds [WebSocketConfig.txQueueHighWaterMarkBytes].
 *    Prevents runaway memory growth even when the producer is faster than
 *    the wire.
 *  - **Stale-callback guards**: every [WebSocketListener] callback verifies
 *    it came from the *current* socket via [AtomicReference.compareAndSet] /
 *    identity check, so a delayed `onClosed` from a replaced connection
 *    cannot accidentally tear down the new healthy one.
 *  - Heartbeat via OkHttp's built-in `pingInterval` (no per-message timer).
 *  - Detailed Timber logging tagged `WS`. Toggle full payload logging via
 *    [WebSocketConfig.logPayloads].
 *
 * ## Threading
 * Public methods are safe to call from any thread. Lifecycle methods
 * ([connect], [disconnect], [cancel], [close]) are serialized via a private
 * intrinsic monitor; the send path is lock-free (it pushes onto a `Channel`).
 * The OkHttp listener callbacks land on OkHttp's dispatcher pool and acquire
 * the same monitor before mutating state.
 *
 * Reconnect scheduling (backoff, auth refresh, fast retry) is delegated to
 * [ReconnectCoordinator], which communicates back through the
 * [ReconnectCoordinator.Host] callback interface.
 *
 * ## Lifecycle
 * The client is meant to be a long-lived, app-scoped object (typically
 * created once via the `ServiceProvider.createWebSocket` factory). Call
 * [close] when you're truly done with it (e.g. ViewModel onCleared,
 * application teardown). Once closed, the instance cannot be reused —
 * create a fresh one if you need another connection.
 *
 * @param baseClient        an [OkHttpClient] to derive the WebSocket-tuned
 *                          variant from. Connection pool, dispatcher, DNS,
 *                          and any application interceptors are reused. The
 *                          client overrides `readTimeout(0)` and applies
 *                          [WebSocketConfig.pingIntervalMillis] on the
 *                          derived builder; **the input client is not
 *                          mutated**.
 * @param config            see [WebSocketConfig].
 * @param authProvider      optional credentials hook. When non-null, the
 *                          client calls [WebSocketAuthProvider.headers] fresh
 *                          on every (re)connect attempt and triggers
 *                          [WebSocketAuthProvider.onAuthFailure] after
 *                          auth failures. Auth failures (as determined by
 *                          [WebSocketAuthProvider.isAuthFailure]) are handled
 *                          by a dedicated refresh-and-retry path that
 *                          bypasses [WebSocketConfig.reconnectStrategy] —
 *                          no backoff, immediate retry after a successful
 *                          refresh. Non-auth failures (e.g. 503) still go
 *                          through the reconnect strategy as usual. See
 *                          [BearerTokenAuthProvider] for the Bearer-token
 *                          implementation that wraps an `IToken` (typically
 *                          your `SessionManager`). **Lives here, not on
 *                          [WebSocketConfig], because it is a stateful
 *                          collaborator — keeping it off the config lets
 *                          `WebSocketConfig` stay a pure value type with
 *                          meaningful equality.**
 * @param connectivityObserver optional. If provided, the client subscribes to
 *                          connectivity changes and triggers a fast retry
 *                          when the network returns. Production should back
 *                          this with [android.net.ConnectivityManager].
 * @param parentContext     coroutine context for the internal scope. Defaults
 *                          to [Dispatchers.IO]. A [SupervisorJob] is always
 *                          added on top so child failures never cancel the
 *                          scope. Pass a test dispatcher in unit tests to
 *                          control time advancement.
 */
class RobustWebSocketClient internal constructor(
    baseClient: OkHttpClient,
    private val config: WebSocketConfig,
    private val authProvider: WebSocketAuthProvider? = null,
    private val connectivityObserver: ConnectivityObserver? = null,
    parentContext: CoroutineContext = Dispatchers.IO,
    /**
     * Single uptime-millis source for connection and reconnect state.
     *
     * Production passes [SystemClock.uptimeMillis] so existing consumers may
     * safely compare state timestamps with the Android uptime clock. Local JVM
     * tests inject a fake because the Android framework stub is not executable.
     */
    private val monotonicNowMillis: () -> Long,
    /**
     * Test seam — produces the underlying [WebSocket] for an outgoing
     * connection attempt. Defaults to OkHttp's real `newWebSocket`. Tests can
     * pass a fake that synthesises `onOpen` immediately and records sent
     * frames in-memory, removing real socket I/O from the critical path.
     *
     * Internal visibility on purpose: this is not a public extension point.
     */
    private val webSocketFactory: (OkHttpClient, Request, WebSocketListener) -> WebSocket =
        { c, r, l -> c.newWebSocket(r, l) },
) : WebSocketClient {

    /**
     * Public constructor — preserves the original API. Production callers
     * use this; the internal constructor above is reserved for tests that
     * need to inject a fake [webSocketFactory].
     */
    constructor(
        baseClient: OkHttpClient,
        config: WebSocketConfig,
        authProvider: WebSocketAuthProvider? = null,
        connectivityObserver: ConnectivityObserver? = null,
        parentContext: CoroutineContext = Dispatchers.IO,
    ) : this(
        baseClient = baseClient,
        config = config,
        authProvider = authProvider,
        connectivityObserver = connectivityObserver,
        parentContext = parentContext,
        monotonicNowMillis = SystemClock::uptimeMillis,
    )

    // ── Tuned OkHttpClient ────────────────────────────────────────────────────
    //
    // newBuilder() so we get a defensive copy of the input client and don't
    // mutate the caller's instance. readTimeout(0) is required for long-lived
    // sockets — otherwise OkHttp closes the connection after the first idle
    // window. pingInterval drives OkHttp's automatic Ping/Pong heartbeat.
    private val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(config.pingIntervalMillis, TimeUnit.MILLISECONDS)
        .build()

    // ── Internal scope ────────────────────────────────────────────────────────
    //
    // Declared first so every other property below is free to reference it
    // (Kotlin initialises properties in declaration order).
    private val scope = CoroutineScope(SupervisorJob() + parentContext)

    // ── State / events ────────────────────────────────────────────────────────

    private val _state = MutableStateFlow<WebSocketState>(WebSocketState.Idle)
    override val state: StateFlow<WebSocketState> get() = _state.asStateFlow()

    /**
     * Lifecycle observability events. Uses `DROP_OLDEST` overflow because
     * these are **observability**, not delivery: a slow subscriber that
     * misses events loses the *oldest* ones first, but the producer never
     * blocks. Missing an `Open` event is harmless — the current state is
     * always available on [state].
     */
    private val _events = MutableSharedFlow<WebSocketEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<WebSocketEvent> get() = _events.asSharedFlow()

    /**
     * Inbound business messages. Uses a **larger buffer** (256) and
     * `SUSPEND` overflow — combined with the `tryEmit` call site in the
     * listener this means: when the buffer fills up, new messages are
     * *dropped and loudly logged* instead of silently displacing older
     * messages (`DROP_OLDEST`) or blocking the OkHttp dispatcher
     * (`emit`).
     *
     * Dropping the **newest** on overflow is the right default for ordered
     * business protocols: drop the message the consumer hasn't seen yet and
     * let the app-level ack machinery retransmit, rather than invalidating
     * state the consumer has already processed from the older messages.
     *
     * For guaranteed delivery, rely on application-level acknowledgements.
     */
    private val _incoming = MutableSharedFlow<IncomingMessage>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: SharedFlow<IncomingMessage> get() = _incoming.asSharedFlow()

    // ── Outgoing buffer (Channel-based, single-consumer) ──────────────────────
    //
    // The channel is the single source of truth for outgoing messages. There
    // is exactly one consumer (the outboxLoop coroutine), which guarantees
    // strict FIFO order on the wire — including across reconnects and across
    // concurrent producers calling send() from different threads.
    //
    // Each message is wrapped in an [Envelope] tagged with the [currentEpoch]
    // value at the time it was enqueued. The consumer drops any envelope
    // whose epoch no longer matches the current epoch — see [disconnect] for
    // why this is needed (it lets a `disconnect → connect` cycle invalidate
    // any in-flight or buffered messages from the previous session without
    // having to synchronously cancel/drain the consumer).

    // Note on Channel overflow modes:
    //  - DropOldest → BufferOverflow.DROP_OLDEST: trySend always succeeds
    //    (oldest is silently displaced).
    //  - DropNewest and Suspend → BufferOverflow.SUSPEND: trySend returns
    //    `false` when full (so the caller learns of rejection); the
    //    *suspending* `send()` blocks until room. The two strategies differ
    //    in convention only — DropNewest callers use the non-suspending
    //    [send] and react to `false`; Suspend callers use [sendSuspending]
    //    and rely on coroutine backpressure.
    private val outbox: Channel<Envelope> = Channel(
        capacity = config.outgoingBufferSize,
        onBufferOverflow = when (config.backpressureStrategy) {
            BackpressureStrategy.DropOldest -> BufferOverflow.DROP_OLDEST
            BackpressureStrategy.DropNewest,
            BackpressureStrategy.Suspend -> BufferOverflow.SUSPEND
        }
    )

    private sealed class OutgoingMessage {
        abstract val sizeBytes: Int
        data class Text(val text: String) : OutgoingMessage() {
            // Wire size is UTF-8 byte count, NOT String.length (which counts
            // UTF-16 code units). For ASCII the two are equal; for anything
            // else `length` under-reports by 2-3x and makes every log line
            // lie. Only used by logSend, so the allocation is fine.
            override val sizeBytes: Int get() = text.toByteArray(Charsets.UTF_8).size
        }
        data class Binary(val bytes: ByteString) : OutgoingMessage() {
            override val sizeBytes: Int get() = bytes.size
        }
    }

    /**
     * Channel envelope: pairs a message with the [currentEpoch] value at the
     * moment the producer called [send] / [sendSuspending]. The consumer
     * compares this against the current epoch in [deliver] and silently
     * drops the message if they no longer match — that's how a `disconnect`
     * invalidates any messages that race past its `stopRequested` check.
     */
    private data class Envelope(val epoch: Long, val msg: OutgoingMessage)

    // ── Internal state (mutated under `lifecycleLock`) ────────────────────────
    //
    // `lifecycleLock` is a Java intrinsic monitor (not a coroutine Mutex),
    // because all the holders are non-suspending lifecycle methods and
    // OkHttp listener callbacks. The lock is held only for state mutations,
    // never around I/O — so it cannot deadlock.

    private val lifecycleLock = Any()

    /**
     * Atomic reference to the *currently authoritative* OkHttp WebSocket.
     * `compareAndSet(stale, null)` in listener callbacks gates state mutation
     * on identity, so a delayed `onClosed` / `onFailure` from a replaced
     * socket cannot pollute the new connection.
     */
    private val socketRef = AtomicReference<WebSocket?>(null)

    /**
     * Set to `true` after [close]; all subsequent operations short-circuit.
     * `@Volatile` is sufficient because we only ever transition false → true
     * once, and readers tolerate a brief stale read.
     */
    @Volatile private var closed: Boolean = false

    /**
     * "Stop" sentinel — set when the user explicitly asked the client to
     * stop ([disconnect]) or the reconnect coordinator gave up. While true,
     * the consumer drains and exits, listener callbacks suppress reconnect,
     * and incoming `send()` is rejected. Cleared by [connect] to allow
     * restart.
     */
    @Volatile private var stopRequested: Boolean = false

    /**
     * Monotonic counter bumped on every [disconnect] / coordinator terminate.
     * Producers (`send` / `sendSuspending`) capture the current value into
     * each [Envelope] *before* checking [stopRequested]; the consumer drops
     * any envelope whose captured epoch no longer matches the current value.
     *
     * **Why a separate signal in addition to `stopRequested`?** Because
     * `stopRequested` can be flipped back to `false` by a subsequent
     * [connect], and that flip can race the consumer waking up from
     * `state.first` after a disconnect. With only `stopRequested`, a
     * message popped before the disconnect could be delivered after the
     * connect — a "ghost" replay from the previous session. The epoch is
     * monotonically increasing, so a stale envelope is still recognisable
     * after the connect has cleared `stopRequested`.
     *
     * `@Volatile` is sufficient: writers hold [lifecycleLock] (so writes
     * are serialised), readers (the producer side) only need monotonic
     * visibility, and 64-bit volatile longs are guaranteed atomic on the
     * JVM. Wraparound is not a real concern (`Long.MAX_VALUE` disconnects
     * is more than the heat death of the universe at any plausible rate).
     */
    @Volatile private var currentEpoch: Long = 0L

    private var outboxJob: Job? = null       // accessed only under lifecycleLock

    // ── Reconnect coordinator ─────────────────────────────────────────────────

    private val reconnect = ReconnectCoordinator(
        config = config,
        authProvider = authProvider,
        scope = scope,
        monotonicNowMillis = monotonicNowMillis,
        host = object : ReconnectCoordinator.Host {
            override val isStopped: Boolean get() = closed || stopRequested
            override fun currentState(): WebSocketState = _state.value
            override fun updateState(state: WebSocketState) { _state.value = state }
            override fun emitEvent(event: WebSocketEvent) { _events.tryEmit(event) }
            override fun launchConnectLocked() = this@RobustWebSocketClient.launchConnectLocked()
            override fun onTerminateLocked(reason: String) {
                stopRequested = true
                currentEpoch += 1
                socketRef.getAndSet(null)?.cancel()
                _state.value = WebSocketState.Disconnected
            }
            override fun withLifecycleLock(block: () -> Unit) =
                synchronized(lifecycleLock, block)
        },
    )

    init {
        // Single long-lived outbox consumer. Lives for the lifetime of the
        // client — disconnect() does NOT cancel it (the consumer drops queued
        // messages via its !stopRequested check instead, see disconnect's
        // KDoc), and only close() ever takes it down for good.
        synchronized(lifecycleLock) { startOutboxLoopLocked() }

        // Network-aware fast retry: when connectivity flips false → true and
        // we are currently in Reconnecting, cancel the in-flight backoff and
        // attempt immediately.
        connectivityObserver?.let { observer ->
            scope.launch {
                observer.observeConnectivity
                    .distinctUntilChanged()
                    .drop(1) // ignore initial replay; only react to transitions
                    .filter { it } // online edge
                    .collect {
                        synchronized(lifecycleLock) { reconnect.fastRetryIfNeeded() }
                    }
            }
        }
    }

    private fun startOutboxLoopLocked() {
        outboxJob?.cancel()
        outboxJob = scope.launch { outboxLoop() }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Open the connection. Idempotent: if the client is already
     * [Connecting][WebSocketState.Connecting], [Connected][WebSocketState.Connected],
     * or [Reconnecting][WebSocketState.Reconnecting], this is a no-op (the
     * existing connection is preserved). Calling after a previous [disconnect]
     * resets the stop flag and starts a fresh connection cycle.
     */
    override fun connect() = synchronized(lifecycleLock) {
        if (closed) {
            Timber.tag(TAG).w("connect() ignored — client is closed")
            return@synchronized
        }
        stopRequested = false
        when (_state.value) {
            is WebSocketState.Connecting,
            is WebSocketState.Connected,
            is WebSocketState.Reconnecting -> {
                Timber.tag(TAG).d("connect() ignored — already in state ${_state.value}")
                return@synchronized
            }
            else -> Unit
        }
        reconnect.resetAttempts()
        // The consumer is normally already alive — disconnect() doesn't
        // cancel it (see disconnect's KDoc). The only path that kills it is
        // close(), and a closed client can't reach this line because of the
        // guard above. The check is a defensive safety net for unforeseen
        // future edits — if outboxJob were ever null/inactive here, we'd
        // restart it instead of silently sending nothing.
        if (outboxJob?.isActive != true) startOutboxLoopLocked()
        launchConnectLocked()
    }

    /**
     * Close the connection gracefully. Sends a Close frame with the given
     * code/reason and transitions to [WebSocketState.Disconnected]. Buffered
     * outgoing messages are dropped — the user explicitly wanted to stop, so
     * stale messages from before the disconnect are not what they want when
     * they next call [connect]. Auto-reconnect is suppressed until [connect]
     * is called again.
     *
     * **Drain semantics.** We deliberately do *not* cancel the outbox
     * consumer or synchronously drain the channel here. An earlier version
     * did, and that created a race: a `send()` call that had already passed
     * the `!stopRequested` guard could `trySend` into the channel *after*
     * the synchronous drain ran, leaving a "ghost" message that would
     * surface on the next [connect]. By keeping the consumer alive and
     * relying on its `!stopRequested` check inside `deliver()`, every
     * message that lands in the channel during the disconnect window — no
     * matter how it slipped past the producer-side guard — is silently
     * dropped by the consumer instead of replayed.
     */
    override fun disconnect(code: Int, reason: String) =
        synchronized(lifecycleLock) {
            if (closed) return@synchronized
            Timber.tag(TAG).i("disconnect(code=$code, reason=\"$reason\")")
            stopRequested = true
            // Bump the epoch BEFORE clearing the socket so any in-flight or
            // already-buffered envelopes are tagged with the now-stale epoch
            // and dropped by the consumer when it next inspects them. The
            // bump is also what closes the race where a producer's check of
            // `stopRequested` raced past disconnect: see [Envelope] and the
            // KDoc on [currentEpoch].
            currentEpoch += 1
            reconnect.cancelPendingReconnect()
            // outboxJob stays alive on purpose — see KDoc above. The consumer
            // will pull every queued envelope off the channel and drop it via
            // the !stopRequested + epoch check inside deliver(), then park
            // on outbox.receive() until the next connect() flips stopRequested
            // back to false.
            socketRef.getAndSet(null)?.close(code, reason)
            _state.value = WebSocketState.Disconnected
        }

    /** See [WebSocketClient.cancel]. */
    override fun cancel() = synchronized(lifecycleLock) {
        if (closed) return@synchronized
        Timber.tag(TAG).i("cancel() — abruptly cancelling socket")
        socketRef.get()?.cancel()
    }

    /**
     * Send a text frame. Returns `true` if the frame was accepted into the
     * outgoing buffer (or, with [BackpressureStrategy.DropOldest], displaced
     * an older buffered frame), `false` if the client is closed/stopped or
     * the buffer rejected it under the configured [BackpressureStrategy].
     *
     * Non-suspending; safe to call from the UI thread.
     *
     * Note: even when this method returns `true`, the message may still be
     * dropped before reaching the wire if a [disconnect] interleaves —
     * `send` reads [currentEpoch] *before* the `stopRequested` check, so
     * any disconnect that runs between the read and the enqueue tags the
     * message with the now-stale epoch and the consumer drops it. This is
     * intentional: it preserves the contract that "messages enqueued
     * before disconnect are not replayed on the next connect", which the
     * app-level acks on received [IncomingMessage] frames already
     * accommodate.
     */
    override fun send(text: String): Boolean {
        // Read the epoch BEFORE the stop check so we tag the envelope with
        // the epoch the producer thought it was sending in. If a concurrent
        // disconnect bumps the epoch between this read and the trySend, the
        // consumer's epoch comparison drops the message — see [Envelope].
        val epoch = currentEpoch
        if (closed || stopRequested) return false
        return outbox.trySend(Envelope(epoch, OutgoingMessage.Text(text))).isSuccess
    }

    /**
     * Send a binary frame. Same buffering / return-value semantics as the
     * text overload.
     */
    override fun send(bytes: ByteString): Boolean {
        val epoch = currentEpoch
        if (closed || stopRequested) return false
        return outbox.trySend(Envelope(epoch, OutgoingMessage.Binary(bytes))).isSuccess
    }

    /**
     * Suspending send — useful with [BackpressureStrategy.Suspend] to apply
     * real backpressure to a coroutine-based producer. With other strategies,
     * behaves the same as the non-suspending [send].
     *
     * Returns `true` if the frame was enqueued into the outbox. Returns
     * `false` — never throws — if the client is [closed] / [stopRequested]
     * either before the call or while the caller was suspended waiting for
     * room (e.g. [close] ran and closed the outbox). This mirrors the
     * non-suspending [send] overload: both report failure via return value,
     * so callers don't have to special-case an exception path on one API
     * and a boolean on the other.
     */
    override suspend fun sendSuspending(text: String): Boolean {
        val epoch = currentEpoch
        if (closed || stopRequested) return false
        return try {
            outbox.send(Envelope(epoch, OutgoingMessage.Text(text)))
            true
        } catch (_: ClosedSendChannelException) {
            // Outbox was closed by close() while we were suspended — treat
            // as a plain "couldn't send, client went away". A structured
            // CancellationException (coroutine cancelled) still propagates,
            // which is the correct behaviour for a suspending function.
            false
        }
    }

    /** See [sendSuspending] (text). */
    override suspend fun sendSuspending(bytes: ByteString): Boolean {
        val epoch = currentEpoch
        if (closed || stopRequested) return false
        return try {
            outbox.send(Envelope(epoch, OutgoingMessage.Binary(bytes)))
            true
        } catch (_: ClosedSendChannelException) {
            false
        }
    }

    /**
     * Tear down the client permanently. Cancels reconnect, closes the socket,
     * cancels the internal scope. The instance cannot be reused after [close];
     * create a new [RobustWebSocketClient] if you need another connection.
     */
    override fun close() = synchronized(lifecycleLock) {
        if (closed) return@synchronized
        closed = true
        stopRequested = true
        Timber.tag(TAG).i("close() — tearing down client")
        reconnect.cancelPendingReconnect()
        outbox.close()
        socketRef.getAndSet(null)?.close(NORMAL_CLOSURE, "client closed")
        _state.value = WebSocketState.Disconnected
        scope.cancel()
    }

    // ── Outbox consumer ───────────────────────────────────────────────────────

    /**
     * Long-lived consumer of the [outbox] channel. There is exactly one
     * instance running on [scope]. It receives messages in FIFO order and
     * delivers them to the current socket, applying both the connection-state
     * gate (waits for [WebSocketState.Connected]) and the wire-level queue
     * depth gate (waits for OkHttp's internal queue to drop below the
     * high-water mark).
     */
    private suspend fun outboxLoop() {
        try {
            while (!closed) {
                val envelope = try {
                    outbox.receive()
                } catch (e: ClosedReceiveChannelException) {
                    Timber.tag(TAG).d("outbox closed; consumer exiting")
                    return
                }
                deliver(envelope)
            }
        } catch (e: CancellationException) {
            // scope cancelled by close() — exit cleanly
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "outbox consumer crashed unexpectedly")
        }
    }

    /**
     * Deliver a single envelope to the wire. Blocks (suspends) until either:
     *  - the message has been handed to OkHttp, OR
     *  - the client is shutting down (returns without sending), OR
     *  - the user has issued a stop (returns without sending — message lost
     *    by the user's own request), OR
     *  - the envelope's epoch is stale (a [disconnect] / coordinator terminate
     *    happened after the producer enqueued it — message dropped).
     *
     * Critically, this method **never returns the message to the channel**.
     * If a transient failure happens (socket dies mid-loop), the consumer
     * keeps retrying *this same message* until success or stop. That
     * preserves order: every later message in the channel waits behind it.
     */
    private suspend fun deliver(envelope: Envelope) {
        val expectedEpoch = envelope.epoch
        val msg = envelope.msg
        while (!closed && !stopRequested && currentEpoch == expectedEpoch) {
            // 1. Wait for a live, Connected socket.
            if (_state.value !is WebSocketState.Connected) {
                state.first {
                    closed || stopRequested ||
                            currentEpoch != expectedEpoch ||
                            it is WebSocketState.Connected ||
                            it is WebSocketState.Disconnected
                }
                continue
            }
            val socket = socketRef.get() ?: continue

            // 2. Wire-level backpressure: pause if OkHttp's send queue is past
            //    the high-water mark, so we don't blow up memory.
            while (socket.queueSize() > config.txQueueHighWaterMarkBytes) {
                Timber.tag(TAG).w(
                    "Wire backpressure: queueSize=${socket.queueSize()} > " +
                        "${config.txQueueHighWaterMarkBytes}; sleeping ${config.txBackpressurePollIntervalMillis}ms"
                )
                delay(config.txBackpressurePollIntervalMillis)
                if (closed || stopRequested || currentEpoch != expectedEpoch) return
                if (socketRef.get() !== socket) break // socket changed; restart
            }
            if (socketRef.get() !== socket) continue

            // 3. Hand off to OkHttp.
            val ok = when (msg) {
                is OutgoingMessage.Text -> socket.send(msg.text)
                is OutgoingMessage.Binary -> socket.send(msg.bytes)
            }
            if (ok) {
                logSend(msg)
                return
            }

            // 4. OkHttp rejected — usually means the socket is dying. Force
            //    the failure path so reconnect kicks in, then loop and retry.
            Timber.tag(TAG).w("WebSocket.send rejected; forcing socket cancel to recover")
            socket.cancel()
            // The next iteration will see state != Connected and wait.
        }
    }

    // ── Internal connect (must hold lifecycleLock) ────────────────────────────

    private fun launchConnectLocked() {
        if (closed) return
        // Defensive: cancel any lingering socket from a prior attempt so its
        // listener stops firing into our state machine.
        socketRef.getAndSet(null)?.cancel()

        _state.value = WebSocketState.Connecting
        Timber.tag(TAG).i("Connecting to ${config.url} (attempt ${reconnect.reconnectAttempt + 1})")

        // Headers come from two sources:
        //  1. Static [WebSocketConfig.headers] — Origin, User-Agent, anything
        //     fixed for the lifetime of the client.
        //  2. Dynamic [WebSocketAuthProvider.headers] — credentials that may
        //     have been refreshed since the last attempt. Computed fresh each
        //     attempt; on conflict, dynamic wins (the provider is the
        //     authoritative source for keys it manages, e.g. Authorization).
        //
        // We capture the dynamic headers into the coordinator's
        // [lastSentHeaders] so onAuthFailure can be told *exactly* what was
        // rejected, which matters for IToken.refreshTokenSync's failedToken
        // dedup contract.
        val dynamicHeaders = authProvider?.headers().orEmpty()
        reconnect.lastSentHeaders = dynamicHeaders
        val mergedHeaders = if (dynamicHeaders.isEmpty()) {
            config.headers
        } else {
            config.headers + dynamicHeaders
        }

        // Static config URL by default; auth providers that authenticate on
        // the upgrade request's query string (e.g. HMAC-signed shaft-api-v2
        // chat WS) override here to produce a fresh signature per attempt.
        val effectiveUrl = authProvider?.dynamicUrl() ?: config.url
        val request = Request.Builder()
            .url(effectiveUrl)
            .apply {
                mergedHeaders.forEach { (k, v) -> addHeader(k, v) }
                if (config.subProtocols.isNotEmpty()) {
                    addHeader("Sec-WebSocket-Protocol", config.subProtocols.joinToString(", "))
                }
            }
            .build()

        // newWebSocket starts the handshake on an OkHttp dispatcher thread
        // and immediately returns the (not-yet-open) WebSocket handle.
        // Delegated through [webSocketFactory] so tests can substitute a
        // fake — production wires this to OkHttpClient::newWebSocket.
        val socket = webSocketFactory(client, request, listener)
        socketRef.set(socket)
    }

    // ── OkHttp listener ───────────────────────────────────────────────────────
    //
    // Every callback that mutates state acquires lifecycleLock and gates on
    // the socket identity, so a delayed callback from a replaced socket
    // cannot pollute the new connection. The read-only callbacks (onMessage)
    // do a cheap identity compare without taking the lock — they only need
    // to filter stale frames, not mutate.

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lifecycleLock) {
                if (closed) return@synchronized
                if (socketRef.get() !== webSocket) {
                    Timber.tag(TAG).d("Ignoring stale onOpen from replaced socket")
                    return@synchronized
                }
                reconnect.onConnectSuccess()
                _state.value = WebSocketState.Connected(monotonicNowMillis())
                Timber.tag(TAG).i("Connected ✓ (HTTP ${response.code})")
            }
            _events.tryEmit(WebSocketEvent.Open)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed) return
            if (socketRef.get() !== webSocket) return // stale
            // UTF-8 byte count to match logSend's units (text.length is
            // UTF-16 code units and under-reports for non-ASCII by 2-3x).
            val sizeBytes = text.toByteArray(Charsets.UTF_8).size
            logRecv("text", sizeBytes)
            if (config.logPayloads) Timber.tag(TAG).d("⇣ text: $text")
            // tryEmit on a SUSPEND-overflow SharedFlow returns false when
            // the buffer is full — log loudly so the drop is visible,
            // rather than silently displacing older messages. The listener
            // callback cannot suspend (it runs on an OkHttp dispatcher
            // thread and blocking here would starve all other sockets
            // sharing the pool), so we accept the drop.
            if (!_incoming.tryEmit(IncomingMessage.Text(text))) {
                Timber.tag(TAG).w(
                    "incoming buffer full; dropped text frame ($sizeBytes B)"
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed) return
            if (socketRef.get() !== webSocket) return // stale
            logRecv("binary", bytes.size)
            if (config.logPayloads) Timber.tag(TAG).d("⇣ binary: ${bytes.hex()}")
            if (!_incoming.tryEmit(IncomingMessage.Binary(bytes))) {
                Timber.tag(TAG).w(
                    "incoming buffer full; dropped binary frame (${bytes.size} B)"
                )
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (closed) return
            if (socketRef.get() !== webSocket) return // stale
            Timber.tag(TAG).i("Server closing: code=$code reason=\"$reason\"")
            _events.tryEmit(WebSocketEvent.Closing(code, reason))
            // Echo the close back so the server's onClosed fires promptly.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lifecycleLock) {
                if (closed) return@synchronized
                // CAS gates the side-effect: only the *current* socket is
                // allowed to drive state transitions.
                if (!socketRef.compareAndSet(webSocket, null)) {
                    Timber.tag(TAG).d("Ignoring stale onClosed from replaced socket")
                    return@synchronized
                }
                Timber.tag(TAG).i("Closed: code=$code reason=\"$reason\"")
                _events.tryEmit(WebSocketEvent.Closed(code, reason))
                reconnect.scheduleReconnect(FailureContext.Closed(code, reason))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lifecycleLock) {
                if (closed) return@synchronized
                if (!socketRef.compareAndSet(webSocket, null)) {
                    Timber.tag(TAG).d("Ignoring stale onFailure from replaced socket")
                    return@synchronized
                }
                val code = response?.code
                Timber.tag(TAG).w(t, "Failure (HTTP $code): ${t.message}")
                _events.tryEmit(WebSocketEvent.Failure(t, code))
                reconnect.scheduleReconnect(FailureContext.Failure(t, code))
            }
        }
    }

    // ── Logging helpers ───────────────────────────────────────────────────────

    private fun logSend(msg: OutgoingMessage) {
        val kind = when (msg) {
            is OutgoingMessage.Text -> "text"
            is OutgoingMessage.Binary -> "binary"
        }
        Timber.tag(TAG).d("⇡ $kind (${msg.sizeBytes} B)")
    }

    private fun logRecv(kind: String, size: Int) {
        Timber.tag(TAG).d("⇣ $kind ($size B)")
    }

    companion object {
        // Tag is in the unified Chat-* prefix family even though the class
        // lives in the generic websocket package — chat is the only consumer
        // today and shared filtering matters more than future-purity.
        private const val TAG = "Chat-WsClient"

        /** RFC 6455 §7.4.1 — normal closure. */
        const val NORMAL_CLOSURE: Int = 1000
    }
}
