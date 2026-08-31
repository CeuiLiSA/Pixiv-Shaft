package ceui.pixiv.websocket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import timber.log.Timber

/**
 * Application-scoped source of network connectivity state, backed by
 * [android.net.ConnectivityManager].
 *
 * ## Shared subscription
 *
 * [observeConnectivity] is exposed as a [SharedFlow] instead of a cold
 * `callbackFlow`. That's deliberate: a cold flow re-executes its builder
 * block on every `collect { }`, which for this class would mean registering
 * a brand-new [ConnectivityManager.NetworkCallback] per subscriber. Multiple
 * consumers (the WebSocket client, UI code that wants to grey out an
 * offline button, etc.) would each install their own system callback,
 * which is both wasteful and — at enough subscribers — bounded by the
 * Android-side hard cap on callback registrations.
 *
 * With `shareIn(WhileSubscribed)` this class registers **one** system
 * callback the first time anyone subscribes, keeps it alive while there is
 * at least one subscriber (plus a 5 s grace period to absorb
 * configuration-change churn), and tears it down after the last
 * subscription goes away. Subsequent subscriptions re-register.
 *
 * @param context        any [Context]; only the application context is held
 *                       internally.
 * @param parentContext  coroutine context for the internal scope. Defaults
 *                       to [Dispatchers.Default]; pass a test dispatcher in
 *                       unit tests.
 */
class NetworkMonitor(
    context: Context,
    parentContext: CoroutineContext = Dispatchers.Default,
) : ConnectivityObserver {

    private val cm = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + parentContext)

    val isConnected: Boolean
        get() {
            // Same SecurityException risk as registerNetworkCallback below —
            // ConnectivityService.getNetworkCapabilities also runs the caller
            // through AppOpsManager.checkPackage. Assume online so the caller
            // falls back to its own retry logic instead of dying on a binder
            // callback thread.
            val caps = try {
                cm.getNetworkCapabilities(cm.activeNetwork)
            } catch (e: SecurityException) {
                Timber.tag(TAG).w(e, "getNetworkCapabilities denied — assuming ONLINE")
                return true
            } ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    // Cold source — one execution of this builder block registers exactly
    // one Android NetworkCallback. shareIn() below guarantees we only ever
    // execute it once at a time regardless of subscriber count.
    private val source: Flow<Boolean> = callbackFlow {
        Timber.tag(TAG).d("registering NetworkCallback")
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.tag(TAG).d("onAvailable: network=%s", network)
                trySend(true)
            }
            override fun onLost(network: Network) {
                // A specific Network was lost, but another (e.g. cellular after
                // WiFi disconnect) may still be active. Re-check the active network
                // instead of blindly emitting false.
                val nowConnected = isConnected
                Timber.tag(TAG).d("onLost: network=%s, fallback connected=%b", network, nowConnected)
                trySend(nowConnected)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Timber.tag(TAG).v("onCapabilitiesChanged: network=%s, internet=%b", network, hasInternet)
                trySend(hasInternet)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // Some OEM ROMs (observed on EMUI, whose HwConnectivityService sits in
        // the crash trace) reject the request inside
        // AppOpsManager.checkPackage with "Package android does not belong to
        // <uid>". This flow runs inside shareIn()'s coroutine, so an escaping
        // throw here reaches the default uncaught handler and kills the whole
        // process — for a signal that is only a fast-reconnect optimisation.
        // Degrade to "no connectivity updates" instead: the initial value is
        // still emitted and RobustWebSocketClient keeps its normal backoff.
        val registered = try {
            cm.registerNetworkCallback(request, callback)
            true
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "registerNetworkCallback denied — connectivity updates disabled")
            false
        }
        // Emit initial state
        val initial = isConnected
        Timber.tag(TAG).i("initial connectivity = %s", if (initial) "ONLINE" else "OFFLINE")
        trySend(initial)
        awaitClose {
            if (!registered) return@awaitClose
            Timber.tag(TAG).d("unregistering NetworkCallback")
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: SecurityException) {
                Timber.tag(TAG).w(e, "unregisterNetworkCallback denied")
            }
        }
    }.distinctUntilChanged()
        // Log every transition AFTER distinctUntilChanged so we only see real
        // edges (not the dozens of redundant onCapabilitiesChanged that fire
        // when WiFi roams between APs at the same capability level).
        .onEach { online ->
            Timber.tag(TAG).i("⇒ %s", if (online) "ONLINE" else "OFFLINE")
        }

    /**
     * Hot, replay-1, multi-subscriber view of connectivity. See the class
     * KDoc for why this is shared instead of cold.
     */
    override val observeConnectivity: SharedFlow<Boolean> = source.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(
            stopTimeoutMillis = 5_000,
            replayExpirationMillis = 0,
        ),
        replay = 1,
    )

    private companion object {
        // Unified Chat-* prefix so `package:mine & tag~:^Chat-` captures all
        // chat-related logs in one filter (incl. the underlying WS transport).
        private const val TAG = "Chat-Network"
    }
}
