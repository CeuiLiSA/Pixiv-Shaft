package ceui.pixiv.auth

import android.os.SystemClock
import ceui.pixiv.safe.auth.AuthLog
import ceui.pixiv.safe.auth.AuthSession
import ceui.pixiv.safe.auth.RefreshCoordinator
import ceui.pixiv.safe.auth.SessionProvider
import ceui.pixiv.safe.auth.TokenStore
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.ShaftHmac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * First-party PixShaft session. This is deliberately separate from
 * [SessionManager], which owns Pixiv's OAuth tokens and its unusual HTTP-400
 * refresh contract.
 */
object AuthSessionManager : SessionProvider {
    private const val BOOTSTRAP_BACKOFF_MS = 60_000L

    private val tokenStore: TokenStore by lazy { TokenStore() }
    private val bootstrapLock = Any()
    private val loadLock = Any()
    private val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loaded = false

    @Volatile
    private var current: AuthSession? = null

    @Volatile
    private var nextBootstrapAtElapsed: Long = 0L

    // Invalidates an in-flight bootstrap/refresh when the Pixiv account changes.
    // Without this generation check, a late response could republish the old
    // account's credentials after logout had already erased them.
    private var stateVersion: Long = 0L

    private val refreshCoordinator by lazy {
        RefreshCoordinator(
            currentAccessToken = ::currentAccessToken,
            performRefresh = ::performRefresh,
        )
    }

    override fun currentAccessToken(): String? {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) {
            // Do not remove/sync MMKV on every protected request while logged
            // out. A persisted session still gets erased exactly once.
            if (currentSession() != null) clearCurrentSession()
            return null
        }
        val session = currentSession() ?: return null
        if (!session.isValidFor(uid)) {
            AuthLog.warning(
                "stored session rejected expectedUid=$uid actualUid=${session.uid} " +
                    "generation=${session.generation}",
            )
            clearIfSession(session.sessionId)
            return null
        }
        // An expired access token is intentionally returned. The server's 401
        // is authoritative and drives TokenAuthenticator; refresh expiry is
        // checked by isValidFor so an unrecoverable pair never goes on wire.
        return session.accessToken
    }

    override fun accessTokenOrBootstrap(): String? {
        currentAccessToken()?.let { return it }
        return bootstrapBlocking()
    }

    override fun refreshAfter401(staleAccessToken: String): String? =
        refreshCoordinator.refresh(staleAccessToken)

    override fun clearCurrentSession() {
        synchronized(loadLock) {
            AuthLog.debug("clearing current session state")
            stateVersion++
            nextBootstrapAtElapsed = 0L
            current = null
            loaded = true
            runCatching { tokenStore.clear() }
                .onFailure { AuthLog.warning("failed to clear local auth session", it) }
        }
    }

    /** Clears immediately, then revokes the server-side refresh family best-effort. */
    fun logoutCurrentSession() {
        val session = synchronized(loadLock) {
            val snapshot = currentSession()
            stateVersion++
            nextBootstrapAtElapsed = 0L
            current = null
            loaded = true
            runCatching { tokenStore.clear() }
                .onFailure { AuthLog.warning("failed to clear local auth session during logout", it) }
            snapshot
        } ?: return

        AuthLog.debug("server logout scheduled uid=${session.uid} generation=${session.generation}")
        logoutScope.launch {
            runCatching {
                val response = AuthNetwork.api.logout(
                    LogoutRequest(
                        refreshToken = session.refreshToken,
                        deviceId = session.deviceId,
                    ),
                ).execute()
                response.closeErrorBody()
                if (response.isSuccessful) {
                    AuthLog.debug("server logout completed uid=${session.uid} status=${response.code()}")
                } else {
                    AuthLog.warning("server logout rejected uid=${session.uid} status=${response.code()}")
                }
            }.onFailure { AuthLog.warning("server session logout failed uid=${session.uid}", it) }
        }
    }

    private fun currentSession(): AuthSession? {
        if (loaded) return current
        synchronized(loadLock) {
            if (!loaded) {
                current = runCatching { tokenStore.load() }
                    .onFailure { AuthLog.warning("failed to load encrypted session", it) }
                    .getOrNull()
                loaded = true
            }
            return current
        }
    }

    private fun bootstrapBlocking(): String? {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return null
        if (SystemClock.elapsedRealtime() < nextBootstrapAtElapsed) {
            AuthLog.debug("bootstrap suppressed by transient-failure backoff uid=$uid")
            return null
        }
        synchronized(bootstrapLock) {
            currentAccessToken()?.let { return it }
            if (!ShaftHmac.isConfigured) return null
            val startedAt = SystemClock.elapsedRealtime()
            if (startedAt < nextBootstrapAtElapsed) return null
            val expectedStateVersion = synchronized(loadLock) { stateVersion }
            AuthLog.debug("session bootstrap started uid=$uid stateVersion=$expectedStateVersion")
            val deviceId = runCatching { tokenStore.deviceId() }
                .onFailure { AuthLog.warning("failed to load durable auth device identity", it) }
                .getOrNull()
            val response = deviceId?.let { createSession(uid, it) }
            val session = if (deviceId == null) null else response?.toSession(uid, deviceId)
            val token = session?.let { persistAndPublish(it, expectedStateVersion)?.accessToken }
            synchronized(loadLock) {
                nextBootstrapAtElapsed = when {
                    stateVersion != expectedStateVersion -> 0L
                    token == null -> startedAt + BOOTSTRAP_BACKOFF_MS
                    else -> 0L
                }
            }
            if (token == null) {
                AuthLog.warning("session bootstrap did not publish credentials uid=$uid")
            } else {
                AuthLog.debug("session bootstrap completed uid=$uid")
            }
            return token
        }
    }

    private fun createSession(uid: Long, deviceId: String): TokenResponse? {
        return try {
            val response = AuthNetwork.api.createSession(
                CreateSessionRequest(
                    uid = uid,
                    deviceId = deviceId,
                ),
            ).execute()
            if (response.isSuccessful) {
                response.body()
            } else {
                AuthLog.warning("session bootstrap rejected uid=$uid status=${response.code()}")
                response.errorBody()?.close()
                null
            }
        } catch (t: Throwable) {
            AuthLog.warning("session bootstrap failed uid=$uid", t)
            null
        }
    }

    private fun performRefresh(): String? {
        val (session, expectedStateVersion) = synchronized(loadLock) {
            (currentSession() ?: return null) to stateVersion
        }
        val uid = SessionManager.loggedInUid
        if (!session.isValidFor(uid)) return null
        AuthLog.debug("session refresh started uid=$uid generation=${session.generation}")
        val attemptId = runCatching {
            tokenStore.refreshAttempt(session.sessionId, session.generation)
        }.onFailure {
            AuthLog.warning("refresh stopped before network; attempt was not durable generation=${session.generation}", it)
        }.getOrNull() ?: return null

        return try {
            val response = AuthNetwork.api.refreshSession(
                attemptId,
                RefreshSessionRequest(
                    refreshToken = session.refreshToken,
                    deviceId = session.deviceId,
                ),
            ).execute()
            if (response.isSuccessful) {
                val fresh = response.body()?.toSession(session.uid, session.deviceId)
                if (fresh == null || fresh.sessionId != session.sessionId || fresh.generation <= session.generation) {
                    AuthLog.warning(
                        "refresh returned an invalid envelope uid=$uid oldGeneration=${session.generation}",
                    )
                    return null
                }
                persistAndPublish(fresh, expectedStateVersion)?.also {
                    AuthLog.debug("session refresh published uid=$uid generation=${it.generation}")
                }?.accessToken
            } else {
                val error = response.errorBody()?.string().orEmpty()
                AuthLog.warning("session refresh rejected uid=$uid status=${response.code()}")
                if (response.code() == 400 && (
                        error.contains("invalid_grant") || error.contains("token_reuse_detected")
                    )
                ) {
                    clearIfSession(session.sessionId)
                }
                null
            }
        } catch (t: Throwable) {
            // Keep the old pair and the persisted idempotency key. If the server
            // rotated but the response was lost, the next 401 can recover it.
            AuthLog.warning("session refresh failed transiently uid=$uid generation=${session.generation}", t)
            null
        }
    }

    private fun persistAndPublish(session: AuthSession, expectedStateVersion: Long): AuthSession? {
        return synchronized(loadLock) {
            if (stateVersion != expectedStateVersion || SessionManager.loggedInUid != session.uid) {
                return@synchronized null
            }
            try {
                tokenStore.save(session)
                current = session
                loaded = true
                // The new pair is already durable and published. A stale attempt
                // belongs to the previous generation and is harmless; failing
                // to remove it must not turn a successful rotation into a 401.
                runCatching { tokenStore.clearRefreshAttempt() }
                    .onFailure { AuthLog.warning("failed to clear stale refresh attempt", it) }
                session
            } catch (t: Throwable) {
                AuthLog.error("refusing unpersisted rotated session uid=${session.uid}", t)
                null
            }
        }
    }

    private fun clearIfSession(sessionId: String) {
        synchronized(loadLock) {
            val existing = currentSession()
            if (existing == null || existing.sessionId == sessionId) {
                stateVersion++
                nextBootstrapAtElapsed = 0L
                current = null
                loaded = true
                runCatching { tokenStore.clear() }
                    .onFailure { AuthLog.warning("failed to clear rejected auth session", it) }
            }
        }
    }
    private fun <T> retrofit2.Response<T>.closeErrorBody() {
        errorBody()?.close()
    }
}
