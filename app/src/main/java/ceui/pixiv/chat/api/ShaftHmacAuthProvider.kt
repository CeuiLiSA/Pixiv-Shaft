package ceui.pixiv.chat.api

import ceui.pixiv.shaftapi.ShaftHmac
import ceui.pixiv.websocket.FailureContext
import ceui.pixiv.websocket.WebSocketAuthProvider
import timber.log.Timber

/**
 * shaft-api-v2 chat-WS auth provider. Per `docs/ws-chat-integration.md` §2:
 *
 * - Handshake URL: `ws://host/api/v1/chat/ws?uid=<long>&ts=<ms>&sig=<hex>&v=<versionCode>`
 * - sig payload: `"${uid}|${ts}"` (uid-routing model — no peer_uid in sig;
 *   1v1 routing happens at the `msg` frame layer via `to_uid`, not at
 *   handshake)
 * - `v` = client app `versionCode`, appended **unsigned** (deliberately NOT in
 *   the sig payload). Keeping it out of the signature is what makes this
 *   backward-compatible: the live server verifies sig over `uid|ts` and ignores
 *   unknown query params, so shipping `v` can't lock new clients out before the
 *   server learns to read it. The server uses `v` to gate `room:"global"`
 *   *delivery* — connections with a missing/too-old `v` (i.e. every
 *   already-shipped build, which sends no `v`) are not fed global broadcasts.
 *   That is the only lever that stops frozen old clients from blindly showing
 *   the public-chat in-app push banner. See `docs/ws-chat-integration.md` §2.1.
 * - Authentication is **query-string only**; headers and cookies are
 *   ignored by the server. Hence the URL has to be re-signed for every
 *   connect attempt — `dynamicUrl()` override.
 *
 * `ts` canonicalisation trap (doc §2.1 #3): the **decimal string** of `ts`
 * goes into both the HMAC payload AND the URL. We compute the string once
 * and reuse it for both, so any future `Long → "1.7e12"` formatter
 * regression in either spot would surface as a unit-test failure rather
 * than a silent `bad_sig` 401.
 *
 * 401 from this provider is fatal — `bad_uid` / `bad_ts` / `bad_sig` /
 * `ts_skew` can't be fixed by retrying the same credentials. `onAuthFailure`
 * returns `false` and tells [RobustWebSocketClient][ceui.pixiv.websocket.RobustWebSocketClient]
 * to surrender (status → [FailureContext.Failure] with httpCode=401).
 */
class ShaftHmacAuthProvider(
    private val baseHttpUrl: String,
    private val uidProvider: () -> Long,
    /** Client app `versionCode`, advertised to the server as an unsigned `&v=`
     *  query param so it can version-gate `room:"global"` delivery (see class
     *  KDoc). Injected rather than read from BuildConfig here to keep the
     *  provider pure/unit-testable. */
    private val appVersionCode: Int,
) : WebSocketAuthProvider {

    /** Shaft-WS doesn't authenticate on headers — only the query string. */
    override fun headers(): Map<String, String> = emptyMap()

    override fun dynamicUrl(): String? {
        val uid = uidProvider()
        if (uid <= 0L) {
            // Not logged in yet (SessionManager hasn't populated loggedInUid).
            // Throw instead of returning null so RobustWebSocketClient's
            // onFailure path kicks in (logged, counted, reconnect-with-backoff).
            // By the next attempt the user has typically signed in.
            throw IllegalStateException("uid not available yet (user not logged in?)")
        }
        val ts = System.currentTimeMillis().toString()
        val sig = ShaftHmac.signHex("$uid|$ts")
        // `v` is appended after the signed params and is intentionally not part
        // of the HMAC — it's a server-side feature-gate hint, not a credential.
        val signed = deriveWsBase(baseHttpUrl) +
            "/api/v1/chat/ws?uid=$uid&ts=$ts&sig=$sig&v=$appVersionCode"
        if (LOG_URLS) Timber.tag(TAG).d("signed URL = %s", signed)
        return signed
    }

    /**
     * 401 on the handshake is fatal for this scheme — `bad_sig` means the
     * key is wrong, `bad_ts` / `ts_skew` means clock is broken, `bad_uid`
     * means SessionManager produced an invalid value. None of those can be
     * fixed by retrying with the same credentials.
     */
    override fun onAuthFailure(failedHeaders: Map<String, String>): Boolean {
        Timber.tag(TAG).w("auth failure — likely bad_sig / ts_skew / bad_uid")
        return false
    }

    companion object {
        private const val TAG = "Chat-Auth"
        /** Set true to dump signed handshake URLs at DEBUG; off by default —
         *  URLs contain the rotating HMAC sig which is mildly sensitive. */
        private const val LOG_URLS = false

        fun deriveWsBase(httpBase: String): String =
            httpBase.trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
    }
}
