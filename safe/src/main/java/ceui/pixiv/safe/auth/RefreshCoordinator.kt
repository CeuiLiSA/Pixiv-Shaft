package ceui.pixiv.safe.auth

/**
 * Single-flight refresh coordinator. It knows nothing about Pixiv, Retrofit or
 * UI state, so every waiting OkHttp request observes the same refresh attempt.
 */
class RefreshCoordinator(
    private val currentAccessToken: () -> String?,
    private val performRefresh: () -> String?,
) {
    private val lock: Any = Any()

    @Volatile
    private var completedAttempts: Long = 0L

    fun refresh(staleAccessToken: String): String? {
        val fast = currentAccessToken()
        if (fast == null) {
            AuthLog.warning("401 recovery stopped: no current session")
            return null
        }
        if (fast != staleAccessToken) {
            AuthLog.debug("401 recovery reused token from completed refresh")
            return fast
        }

        val attemptsWhenQueued = completedAttempts
        synchronized(lock) {
            val current = currentAccessToken() ?: return null
            if (current != staleAccessToken) {
                AuthLog.debug("401 waiter observed refreshed token")
                return current
            }
            if (completedAttempts != attemptsWhenQueued) {
                AuthLog.warning("401 waiter observed a failed refresh attempt")
                return null
            }
            AuthLog.debug("single-flight refresh started")
            return try {
                performRefresh().also {
                    if (it == null) {
                        AuthLog.warning("single-flight refresh finished without a token")
                    } else {
                        AuthLog.debug("single-flight refresh completed")
                    }
                }
            } finally {
                completedAttempts++
            }
        }
    }
}
