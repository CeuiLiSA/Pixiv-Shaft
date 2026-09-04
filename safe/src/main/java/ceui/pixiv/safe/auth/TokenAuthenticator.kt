package ceui.pixiv.safe.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Handles one server-authoritative 401 refresh and one replay, never more. */
class TokenAuthenticator(
    private val sessions: SessionProvider,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (!AuthRoutes.requiresSession(response.request.url.encodedPath)) return null
        val path = response.request.url.encodedPath
        val responseCount = responseCount(response)
        if (responseCount >= 2) {
            AuthLog.warning("401 replay stopped to prevent a loop path=$path responses=$responseCount")
            return null
        }

        val reason = response.header("X-Pixshaft-Auth-Error")
        val refreshable = response.header("X-Pixshaft-Auth-Refreshable") == "true"
        AuthLog.warning("401 received path=$path reason=${reason ?: "missing"} refreshable=$refreshable")
        if (reason == "session_revoked" || reason == "session_expired") {
            AuthLog.warning("terminal server session state; clearing local credentials reason=$reason")
            sessions.clearCurrentSession()
            return null
        }

        val staleToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }

        val freshToken = when {
            staleToken == null && reason == "auth_required" -> {
                AuthLog.debug("401 recovery bootstrapping a missing session")
                sessions.accessTokenOrBootstrap()
            }
            staleToken != null && refreshable ->
                sessions.refreshAfter401(staleToken)
            else -> null
        }
        if (freshToken == null) {
            AuthLog.warning("401 recovery unavailable path=$path reason=${reason ?: "missing"}")
            return null
        }

        AuthLog.debug("401 replay prepared with fresh bearer path=$path")
        return response.request.newBuilder()
            .header("Authorization", "Bearer $freshToken")
            .header("X-Pixshaft-Auth-Version", "2")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
