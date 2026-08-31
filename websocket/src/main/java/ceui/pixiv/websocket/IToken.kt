package ceui.pixiv.websocket

interface IToken {
    fun getAuthToken(): String?
    fun getRefreshToken(): String?

    /**
     * Atomically refresh the auth token if the caller's [failedAuthToken] is
     * still the one in the store. Called by [TokenAuthenticator] on a
     * background thread when a 401 is received.
     *
     * ## Contract
     *
     * Implementations **must** serialise concurrent calls and short-circuit
     * already-refreshed callers. The expected behaviour is:
     *
     * 1. If the currently stored auth token is non-null and differs from
     *    [failedAuthToken], another caller has already refreshed in the
     *    meantime — return that current token without performing a new
     *    refresh.
     * 2. Otherwise, perform the actual refresh (network call against the
     *    auth server), persist the result, and return the new token.
     * 3. If the refresh itself fails (e.g. the refresh token is rejected),
     *    return `null` to trigger force logout.
     *
     * The serialisation guarantee belongs to the [IToken] implementation,
     * not to [TokenAuthenticator]: when several [ApiClient] instances share
     * the same [IToken], the lock has to be on the shared state, not on
     * any single authenticator instance, otherwise concurrent 401s on
     * different clients each issue an independent refresh and race to
     * overwrite the store.
     *
     * @param failedAuthToken the auth token that the caller's request was
     *                        carrying when it received a 401. May be `null`
     *                        if the request had no auth header.
     * @return the auth token the caller should retry with, or `null` if no
     *         valid token can be obtained (force logout).
     */
    fun refreshTokenSync(failedAuthToken: String?): String?
}
