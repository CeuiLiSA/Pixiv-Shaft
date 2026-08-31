package ceui.pixiv.websocket

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okio.ByteString
import timber.log.Timber

/**
 * App-scoped coordinator that ties the WebSocket connection lifecycle to the
 * user's login session.
 *
 * ## Behaviour
 *
 * - **Login** ([SessionManager.loggedIn] emits `true`): creates a fresh
 *   [WebSocketClient] via [createClient] and calls [connect][WebSocketClient.connect].
 *   From this point on, the client's own reconnect strategy handles transient
 *   failures (network drops, 503s, token refresh) automatically.
 *
 * - **Logout** ([SessionManager.loggedIn] emits `false`): calls
 *   [close][WebSocketClient.close] on the current client, releasing
 *   all resources (socket, coroutines, outbox). The client is not reusable —
 *   a new one is created on the next login.
 *
 * ## Single source of truth: the `loggedIn` flow
 *
 * Listen to **one** login signal only. An earlier design also listened to a
 * separate "force logout" event channel as a secondary "tear down now" trigger
 * and that created a state-desync hazard: if the force-logout event fired but
 * the UI layer failed to follow up by flipping the login state, `loggedIn`
 * would stay `true` while the WebSocket was permanently torn down, with **no
 * re-activation trigger** — the user could not reconnect without a full
 * logout/login cycle.
 *
 * So: whoever handles forced-logout flows at the UI / app layer is responsible
 * for flipping the login state as part of that handling. That triggers
 * `loggedIn = false`, which this class observes and acts on.
 *
 * ## Thread safety
 *
 * The single observer runs on [Dispatchers.Main.immediate], which serialises
 * [activate] / [deactivate] on the main looper. No lock is needed.
 *
 * ## Usage
 *
 * ```kotlin
 * // Observe lifecycle events (auto-switches when the underlying client
 * // is replaced):
 * webSocketManager.events.collect(object : WebSocketEventListener {
 *     override fun onOpen() { showConnectedBadge() }
 *     override fun onReconnectScheduled(attempt: Int, delayMillis: Long) {
 *         showReconnectingBadge(attempt)
 *     }
 * })
 *
 * // Observe received business messages (separate flow so call sites
 * // cannot accidentally conflate messages with lifecycle events):
 * webSocketManager.incoming.collect { msg ->
 *     when (msg) {
 *         is IncomingMessage.Text -> handleMessage(msg.text)
 *         is IncomingMessage.Binary -> handleBinary(msg.bytes)
 *     }
 * }
 *
 * // Observe connection state:
 * webSocketManager.state.collect { state -> updateUi(state) }
 *
 * // One-shot send:
 * webSocketManager.send("hello")
 * ```
 *
 * @param loggedIn         source of login/logout signals — typically derived
 *                         from the app's session/auth store. Emissions of
 *                         `true` activate a fresh client, `false` tears it
 *                         down. This is the *only* signal this class listens to.
 * @param createClient     factory that builds a fresh [WebSocketClient] (with
 *                         the auth provider already wired) for the active
 *                         session.
 * @param config           static config for the app's primary WebSocket endpoint
 * @param parentContext    coroutine context for the internal scope. Defaults
 *                         to [Dispatchers.Main.immediate]. Pass a test
 *                         dispatcher in unit tests.
 */
class WebSocketManager(
    private val loggedIn: Flow<Boolean>,
    private val createClient: (WebSocketConfig) -> WebSocketClient,
    private val config: WebSocketConfig,
    parentContext: CoroutineContext = Dispatchers.Main.immediate,
) {

    private val scope = CoroutineScope(SupervisorJob() + parentContext)

    private val _activeClient = MutableStateFlow<WebSocketClient?>(null)

    /**
     * The currently live [WebSocketClient], or `null` when the user is not
     * logged in.
     *
     * **`internal` on purpose.** Production callers must go through [state],
     * [events], [incoming], [send], and [sendSuspending] — reaching in and
     * holding a direct client reference would let a caller call
     * [WebSocketClient.close] / [WebSocketClient.disconnect] out from under
     * the manager, silently breaking the "client lifecycle follows login
     * state" invariant this class exists to enforce. Unit tests in the same
     * module are the only legitimate consumers; they use it to assert
     * which client instance is currently active.
     */
    internal val activeClient: StateFlow<WebSocketClient?> = _activeClient.asStateFlow()

    /**
     * Connection state of the current session's WebSocket. Automatically
     * switches to the new client on re-login. Falls back to
     * [WebSocketState.Idle] when no session is active.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<WebSocketState> = _activeClient
        .flatMapLatest { it?.state ?: MutableStateFlow(WebSocketState.Idle) }
        .stateIn(scope, SharingStarted.Eagerly, WebSocketState.Idle)

    /**
     * Lifecycle events from the current session's WebSocket. Automatically
     * switches to the new client on re-login. Emits nothing when no session
     * is active.
     *
     * For received business messages, collect [incoming] instead — the two
     * flows are deliberately split so a call site handling one cannot
     * accidentally miss the other.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<WebSocketEvent> = _activeClient
        .flatMapLatest { it?.events ?: emptyFlow() }

    /**
     * Received business messages from the current session's WebSocket.
     * Automatically switches to the new client on re-login. Emits nothing
     * when no session is active.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val incoming: Flow<IncomingMessage> = _activeClient
        .flatMapLatest { it?.incoming ?: emptyFlow() }

    @Volatile
    private var started: Boolean = false

    /**
     * Set by [shutdown]. All public mutating methods ([start], [send],
     * [sendSuspending]) `check` this flag and fail loudly if it is true —
     * once shut down, a [WebSocketManager] cannot be reused. The fail-loudly
     * behaviour exists so that an accidental [shutdown] + subsequent reuse
     * is caught at the call site instead of degrading into a silent
     * "connection never comes back" zombie.
     */
    private val shutdownFlag = AtomicBoolean(false)
    private val isShutdown: Boolean get() = shutdownFlag.get()

    /**
     * Start observing session state. Call once from
     * [Application.onCreate][android.app.Application.onCreate] after all
     * dependencies are initialised (MMKV, Timber, etc.). Subsequent calls
     * are ignored.
     *
     * @throws IllegalStateException if [shutdown] has already been called.
     */
    fun start() {
        check(!isShutdown) { "WebSocketManager has been shut down and cannot be reused" }
        if (started) return
        started = true
        scope.launch {
            loggedIn.collect { isLoggedIn ->
                if (isLoggedIn) activate() else deactivate()
            }
        }
    }

    /**
     * Send a text frame on the current session's WebSocket.
     * Returns `false` if there is no active session or the send was rejected.
     *
     * @throws IllegalStateException if [shutdown] has already been called.
     */
    fun send(text: String): Boolean {
        check(!isShutdown) { "WebSocketManager has been shut down" }
        return _activeClient.value?.send(text) ?: false
    }

    /**
     * Send a binary frame on the current session's WebSocket.
     * Returns `false` if there is no active session or the send was rejected.
     *
     * @throws IllegalStateException if [shutdown] has already been called.
     */
    fun send(bytes: ByteString): Boolean {
        check(!isShutdown) { "WebSocketManager has been shut down" }
        return _activeClient.value?.send(bytes) ?: false
    }

    /**
     * Suspending text send on the current session's WebSocket — applies real
     * backpressure when the configured
     * [BackpressureStrategy] is `Suspend`.
     *
     * Returns `true` on successful enqueue. Returns `false` — never throws —
     * if there is no active session, or if the underlying client is closed /
     * stopped (including becoming closed while the caller was suspended
     * waiting for room). Same convention as [send], so feature code can
     * treat the two the same way without an exception path to special-case.
     *
     * @throws IllegalStateException if [shutdown] has already been called.
     */
    suspend fun sendSuspending(text: String): Boolean {
        check(!isShutdown) { "WebSocketManager has been shut down" }
        val client = _activeClient.value ?: return false
        return client.sendSuspending(text)
    }

    /**
     * Suspending binary send on the current session's WebSocket.
     * See [sendSuspending] (text) for semantics.
     *
     * @throws IllegalStateException if [shutdown] has already been called.
     */
    suspend fun sendSuspending(bytes: ByteString): Boolean {
        check(!isShutdown) { "WebSocketManager has been shut down" }
        val client = _activeClient.value ?: return false
        return client.sendSuspending(bytes)
    }

    /**
     * Create and connect a client for the current session. Closes any
     * pre-existing client first and unconditionally builds a fresh one —
     * re-login is treated as "clean slate", no matter what state the old
     * client was in.
     *
     * **Why always rebuild, even when the old client is `Connected`?**
     * Because [loggedIn] may be a [kotlinx.coroutines.flow.SharedFlow] (not a
     * StateFlow), a re-login *without* an intervening logout can emit `true`
     * while the previous session's client is still `Connected`. That old
     * client is already carrying the **previous session's** auth headers;
     * keeping it alive would mean REST requests go out with the new token
     * while the WebSocket keeps running on the old one — a silent session
     * split. Tearing down unconditionally costs one handshake and avoids
     * that whole class of bug.
     *
     * Reconnecting clients are torn down for the same reason plus a second:
     * they may be trapped on something specific to the previous session
     * (fatal close code, exhausted auth refresh budget, routing tier the
     * user has since moved off) and inheriting that half-dead retry loop
     * would silently deny the user the clean slate they asked for.
     */
    private fun activate() {
        val existing = _activeClient.value
        if (existing != null) {
            // Drop the reference *before* closing so any state change the
            // close triggers on the old client doesn't get re-observed as
            // "the current session's" state. The new client takes its
            // place below.
            _activeClient.value = null
            existing.close()
        }
        val client = createClient(config)
        _activeClient.value = client
        client.connect()
        Timber.tag(TAG).i("Session active — WebSocket connected")
    }

    /**
     * Tear down the current client. After this, [activeClient] emits `null`.
     */
    private fun deactivate() {
        val client = _activeClient.value ?: return
        _activeClient.value = null
        client.close()
        Timber.tag(TAG).i("Session ended — WebSocket closed")
    }

    /**
     * Permanent teardown — tear down the current client and cancel the
     * internal scope. Called if the [Application][android.app.Application]
     * is destroyed (rare outside of tests).
     *
     * **One-way switch.** After [shutdown] returns, every subsequent call
     * to [start], [send], or [sendSuspending] throws
     * [IllegalStateException]. This is deliberate: an earlier version
     * silently cancelled the scope but left [started] set, which meant a
     * caller could call [shutdown] by mistake and then wonder why the
     * WebSocket never came back on the next login — the `loggedIn`
     * collector had been cancelled with the scope and could no longer react
     * to anything. Fail-loud is the right default for an irreversible
     * lifecycle transition.
     *
     * Idempotent: calling [shutdown] more than once is a no-op.
     */
    fun shutdown() {
        // compareAndSet, not check-then-set: concurrent shutdown() callers
        // must not both slip past the guard and double-close the client.
        if (!shutdownFlag.compareAndSet(false, true)) return
        deactivate()
        scope.cancel()
    }

    companion object {
        private const val TAG = "WebSocketMgr"
    }
}
