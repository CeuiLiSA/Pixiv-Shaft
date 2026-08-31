package ceui.pixiv.websocket


/**
 * [WebSocketAuthProvider] that signs the WebSocket upgrade with a Bearer
 * token (or any other scheme — see [scheme]) pulled from an [IToken], and
 * delegates refresh to the same [IToken.refreshTokenSync] contract that
 * the REST `TokenAuthenticator` uses.
 *
 * Sharing one [IToken] (typically `SessionManager`) between REST and
 * WebSocket gets you concurrent-refresh dedup for free: the
 * [IToken] implementation owns the refresh lock, so a 401 from REST and a
 * 401 from WebSocket arriving simultaneously will end up calling
 * `refreshTokenSync` on the same lock and only one network refresh will
 * actually fire.
 *
 * @param iToken     the credential store. Most apps want to pass the same
 *                   `SessionManager` they use for REST so refresh is
 *                   shared.
 * @param headerName the HTTP header to set; defaults to `Authorization`.
 * @param scheme     the scheme prefix (with trailing space if any); defaults
 *                   to `"Bearer "`. Use `""` for raw-token schemes, or e.g.
 *                   `"Token "` for Django-style tokens.
 */
class BearerTokenAuthProvider(
    private val iToken: IToken,
    private val headerName: String = "Authorization",
    private val scheme: String = "Bearer ",
) : WebSocketAuthProvider {

    override fun headers(): Map<String, String> {
        val token = iToken.getAuthToken() ?: return emptyMap()
        return mapOf(headerName to "$scheme$token")
    }

    override fun onAuthFailure(failedHeaders: Map<String, String>): Boolean {
        // Strip the scheme prefix so the IToken implementation receives the
        // raw token it stored — that's the value its dedup contract compares
        // against. If the failed request had no auth header (token was null
        // when we connected), pass null and let IToken decide whether to do
        // an unconditional refresh.
        val failedToken = failedHeaders[headerName]?.removePrefix(scheme)
        return iToken.refreshTokenSync(failedToken) != null
    }
}
