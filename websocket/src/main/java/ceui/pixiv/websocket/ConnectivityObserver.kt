package ceui.pixiv.websocket

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over network-connectivity observation. Production should back
 * this with Android's [android.net.ConnectivityManager]; tests can supply a
 * fake with a controllable [observeConnectivity] flow.
 *
 * [RobustWebSocketClient] subscribes to [observeConnectivity] to short-circuit
 * backoff delays when the network comes back online.
 */
interface ConnectivityObserver {

    /**
     * A hot flow that emits `true` when the device has internet connectivity
     * and `false` when it does not. Emissions are de-duplicated so consecutive
     * identical values are suppressed.
     */
    val observeConnectivity: Flow<Boolean>
}
