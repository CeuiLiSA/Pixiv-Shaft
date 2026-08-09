package ceui.pixiv.chat.api

import android.content.Context
import ceui.lisa.BuildConfig
import ceui.pixiv.session.SessionManager
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import ceui.pixiv.websocket.ExponentialBackoffWithJitter
import ceui.pixiv.websocket.AppNetworkMonitor
import ceui.pixiv.websocket.NetworkMonitor
import ceui.pixiv.websocket.RobustWebSocketClient
import ceui.pixiv.websocket.WebSocketClient
import ceui.pixiv.websocket.WebSocketConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Factory for the shaft-api-v2 chat [WebSocketClient]. Wires:
 *  - [ShaftHmacAuthProvider] (signs the upgrade URL per attempt)
 *  - [NetworkMonitor]        (fast-reconnect on OFFLINE → ONLINE)
 *  - OkHttp with `pingInterval=30s` + `readTimeout=0` (per docs §3.1)
 *  - [ExponentialBackoffWithJitter] reconnect strategy
 *
 * The placeholder URL in [WebSocketConfig] is required by the config's
 * `init` validator (must start with `ws://` / `wss://`) but is **always
 * overridden** at connect time via [ShaftHmacAuthProvider.dynamicUrl].
 */
object ShaftChatWsClient {

    /**
     * Build a fresh, not-yet-connected chat WS client.
     *
     * **Sole caller** is [ShaftChatGateway]'s `createClient` lambda passed
     * to [ceui.pixiv.websocket.WebSocketManager]. The manager calls this
     * exactly once per "activation" (chat is anonymous, pinned to
     * activation=`true` for process lifetime, so one client per process).
     * **Don't call from anywhere else** — fragment-local construction
     * would put a second WS client on the wire and break the single-
     * lifecycle invariant the gateway exists to enforce.
     */
    fun create(context: Context): WebSocketClient {
        val authProvider = ShaftHmacAuthProvider(
            baseHttpUrl = BuildConfig.SHAFT_EVENTS_BASE_URL,
            secretAscii = BuildConfig.SHAFT_EVENTS_HMAC,
            // Pixiv login uid identifies the WS — handshake sig is over
            // `${uid}|${ts}`. Server `UID_DECIMAL_RE` requires uid > 0; if
            // SessionManager hasn't been populated yet the auth provider
            // throws and RobustWebSocketClient routes through onFailure +
            // exponential backoff until the user signs in.
            uidProvider = { SessionManager.loggedInUid },
            // Advertised as unsigned `&v=` so the server can version-gate
            // `room:"global"` delivery (old builds send no `v` → no global →
            // no blind public-chat push banner). See ShaftHmacAuthProvider.
            appVersionCode = BuildConfig.VERSION_CODE,
        )

        val placeholderUrl = ShaftHmacAuthProvider.deriveWsBase(
            BuildConfig.SHAFT_EVENTS_BASE_URL
        ) + "/api/v1/chat/ws"

        val okHttpBuilder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // readTimeout is enforced inside RobustWebSocketClient (set to 0
            // there). writeTimeout is fine to set on the base client.
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (BuildConfig.IS_DEBUG_MODE) {
            // Logs the upgrade handshake (101 / 401 / 429 / 503 — see docs
            // §1.1). OkHttp drops the interceptor after the upgrade succeeds
            // so this never logs business frames; those come from
            // RobustWebSocketClient's payload logging below.
            okHttpBuilder.addInterceptor(
                HttpLoggingInterceptor { Timber.tag("Chat-Handshake").i(it) }
                    .apply { level = HttpLoggingInterceptor.Level.HEADERS }
            )
        }
        val okHttp = okHttpBuilder.build()

        val config = WebSocketConfig(
            url = placeholderUrl,
            pingIntervalMillis = TimeUnit.SECONDS.toMillis(30),
            reconnectStrategy = ExponentialBackoffWithJitter(
                initialDelayMillis = 1_000,
                maxDelayMillis = 30_000,
                multiplier = 2.0,
                jitterFactor = 0.2,
                maxAttempts = Int.MAX_VALUE,
            ),
            // 401 from this server is fatal (bad_sig / bad_ts / ts_skew / bad_client_id
            // all unfixable by retry with same credentials). ShaftHmacAuthProvider.onAuthFailure
            // already returns false; this just keeps the budget tight for safety.
            maxAuthRefreshAttempts = 0,
            // Dump every WS frame body to logcat on tag "WS" (debug only).
            // Combined with WsChatMessageStream's INFO-level frame summaries
            // this gives full visibility into what crosses the wire.
            logPayloads = BuildConfig.IS_DEBUG_MODE,
        )

        return RobustWebSocketClient(
            baseClient = okHttp,
            config = config,
            authProvider = authProvider,
            // 进程级共用（见 AppNetworkMonitor）：各自 new 一个等于各注册一个系统 NetworkCallback。
            connectivityObserver = AppNetworkMonitor.get(context),
        )
    }
}
