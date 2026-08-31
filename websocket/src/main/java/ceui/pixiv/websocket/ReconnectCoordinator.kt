package ceui.pixiv.websocket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Manages reconnection scheduling, auth-failure refresh, and network-aware
 * fast retry for [RobustWebSocketClient].
 *
 * Extracted so the main client class focuses on socket I/O and message
 * buffering, while this coordinator owns the "when and how to reconnect"
 * decisions.
 *
 * ## Threading
 *
 * All public methods are called **under the host's lifecycle lock** unless
 * noted otherwise. Coroutines launched by this coordinator re-acquire the
 * lock via [Host.withLifecycleLock] before mutating shared state.
 */
internal class ReconnectCoordinator(
    private val config: WebSocketConfig,
    private val authProvider: WebSocketAuthProvider?,
    private val scope: CoroutineScope,
    private val monotonicNowMillis: () -> Long,
    private val host: Host,
) {

    /**
     * Callback interface through which the coordinator drives state mutations
     * on [RobustWebSocketClient]. Every method that ends with `Locked` is
     * guaranteed to be called under the host's lifecycle lock.
     */
    interface Host {
        /** `true` when the client is permanently closed or stop-requested. */
        val isStopped: Boolean
        fun currentState(): WebSocketState
        fun updateState(state: WebSocketState)
        fun emitEvent(event: WebSocketEvent)

        /** Initiate a new OkHttp WebSocket connection. Called under lock. */
        fun launchConnectLocked()

        /**
         * Transition to terminal [WebSocketState.Disconnected]: set stop flag,
         * bump epoch, cancel the current socket. Called under lock.
         */
        fun onTerminateLocked(reason: String)

        /** Execute [block] inside the host's lifecycle lock. */
        fun withLifecycleLock(block: () -> Unit)
    }

    // ── State (accessed only under the host's lifecycle lock) ────────────────

    /**
     * 1-based attempt counter. Monotonically increasing within a reconnect
     * cycle; reset to 0 by [onConnectSuccess] / [resetAttempts].
     */
    var reconnectAttempt: Int = 0
        private set

    /**
     * Headers sent on the most recent connect attempt. Set by the host in
     * `launchConnectLocked` so [scheduleAuthRefresh] can pass the exact
     * rejected credentials to [WebSocketAuthProvider.onAuthFailure].
     */
    var lastSentHeaders: Map<String, String> = emptyMap()

    private var consecutiveAuthFailures: Int = 0
    private var reconnectJob: Job? = null

    // ── Public API (all called under lock) ───────────────────────────────────

    /** Reset counters after a successful handshake (onOpen). */
    fun onConnectSuccess() {
        reconnectAttempt = 0
        consecutiveAuthFailures = 0
    }

    /** Reset attempt counter for a fresh user-initiated [connect]. */
    fun resetAttempts() {
        reconnectAttempt = 0
    }

    /** Cancel any in-flight backoff delay or auth-refresh job. */
    fun cancelPendingReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * Evaluate [failure] and schedule the appropriate next step: backoff
     * retry, auth refresh, or terminal give-up.
     *
     * Called under the host's lifecycle lock from the OkHttp listener
     * callbacks (`onClosed`, `onFailure`).
     */
    fun scheduleReconnect(failure: FailureContext) {
        if (host.isStopped) return

        // Always bump the global attempt counter so the UI-facing
        // Reconnecting.attempt is monotonically increasing regardless of
        // whether this is an auth or non-auth retry.
        reconnectAttempt += 1

        // ── Auth-failure fast path ───────────────────────────────────────
        val provider = authProvider
        if (provider != null && provider.isAuthFailure(failure)) {
            if (consecutiveAuthFailures >= config.maxAuthRefreshAttempts) {
                Timber.tag(TAG).e(
                    "Auth failure persists after $consecutiveAuthFailures " +
                        "refresh attempt(s); giving up: ${failure.message}"
                )
                terminate("auth refresh exhausted: ${failure.message}")
                return
            }
            consecutiveAuthFailures += 1
            scheduleAuthRefresh(provider, failure)
            return
        }

        // ── Normal backoff path ──────────────────────────────────────────
        val delayMs = config.reconnectStrategy.nextDelayMillis(reconnectAttempt, failure)
        if (delayMs == null) {
            Timber.tag(TAG).e(
                "Reconnect strategy gave up after $reconnectAttempt " +
                    "attempt(s): ${failure.message}"
            )
            terminate("reconnect strategy rejected: ${failure.message}")
            return
        }

        val nextAt = monotonicNowMillis() + delayMs
        host.updateState(
            WebSocketState.Reconnecting(
                attempt = reconnectAttempt,
                delayMillis = delayMs,
                nextAttemptAtMillis = nextAt,
                lastFailure = failure,
            )
        )
        host.emitEvent(WebSocketEvent.ReconnectScheduled(reconnectAttempt, delayMs))
        Timber.tag(TAG).w("Reconnect attempt $reconnectAttempt scheduled in ${delayMs}ms")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            try {
                delay(delayMs)
            } catch (_: CancellationException) {
                return@launch
            }
            host.withLifecycleLock {
                if (host.isStopped) return@withLifecycleLock
                // If something else (fast-retry, manual connect) already
                // moved us off Reconnecting, abort — there is already a
                // connect in flight.
                if (host.currentState() !is WebSocketState.Reconnecting) {
                    return@withLifecycleLock
                }
                host.launchConnectLocked()
            }
        }
    }

    /**
     * Short-circuit the current backoff when the network comes back.
     * Called under the host's lifecycle lock.
     */
    fun fastRetryIfNeeded() {
        if (host.isStopped) return
        if (host.currentState() !is WebSocketState.Reconnecting) return
        Timber.tag(TAG).i("Network is back; cancelling backoff and reconnecting now")
        reconnectJob?.cancel()
        reconnectJob = null
        host.launchConnectLocked()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Refresh credentials in response to an auth failure, then immediately
     * reconnect. Bypasses normal backoff because auth failures aren't
     * transient in the network sense — either the refresh works (retry
     * immediately) or it doesn't (give up immediately).
     *
     * The [WebSocketAuthProvider.onAuthFailure] call may block on a network
     * refresh. We dispatch it onto [scope] **outside** the lifecycle lock,
     * then re-acquire the lock via [Host.withLifecycleLock] for the
     * post-refresh state mutation.
     */
    private fun scheduleAuthRefresh(
        provider: WebSocketAuthProvider,
        failure: FailureContext,
    ) {
        val nextAt = monotonicNowMillis()
        host.updateState(
            WebSocketState.Reconnecting(
                attempt = reconnectAttempt,
                delayMillis = 0,
                nextAttemptAtMillis = nextAt,
                lastFailure = failure,
            )
        )
        host.emitEvent(WebSocketEvent.ReconnectScheduled(reconnectAttempt, 0))
        Timber.tag(TAG).i(
            "Auth failure (${failure.message}); refreshing credentials " +
                "(attempt $consecutiveAuthFailures of ${config.maxAuthRefreshAttempts})"
        )

        // Snapshot the headers we sent on the failing attempt — we have the
        // lock right now so this read is safe; the coroutine below runs
        // outside the lock and we don't want it racing future writes.
        val failedHeaders = lastSentHeaders

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val refreshed = try {
                provider.onAuthFailure(failedHeaders)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "WebSocketAuthProvider.onAuthFailure threw")
                false
            }
            host.withLifecycleLock {
                if (host.isStopped) return@withLifecycleLock
                if (!refreshed) {
                    Timber.tag(TAG).e("Credential refresh failed; terminating")
                    terminate("auth refresh failed: ${failure.message}")
                    return@withLifecycleLock
                }
                // The user might have called connect() / disconnect() while
                // we were off-lock — only proceed if we're still in the
                // Reconnecting state we set up above.
                if (host.currentState() !is WebSocketState.Reconnecting) {
                    return@withLifecycleLock
                }
                Timber.tag(TAG).i("Credentials refreshed; reconnecting now")
                host.launchConnectLocked()
            }
        }
    }

    private fun terminate(reason: String) {
        Timber.tag(TAG).e("terminate: $reason")
        reconnectJob?.cancel()
        reconnectJob = null
        host.onTerminateLocked(reason)
    }

    companion object {
        private const val TAG = "WS"
    }
}
