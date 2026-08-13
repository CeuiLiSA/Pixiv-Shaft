package ceui.lisa.repo

import ceui.loxia.Client
import ceui.loxia.Nana7miPayload
import ceui.loxia.Nana7miResult
import ceui.loxia.fetchNana7mi
import ceui.pixiv.actions.AccountOnlineReportOutbox
import ceui.pixiv.login.InvalidRefreshTokenException
import ceui.pixiv.login.PixivLogin
import ceui.pixiv.session.SessionManager
import io.reactivex.Observable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Semaphore

/**
 * One borrowed Pixiv account for one search-repository instance.
 *
 * The account never enters [SessionManager]: it is used only through an explicit Authorization
 * header. Both illustration and novel search share this class so first-page, pagination, refresh,
 * and durable re-report behavior cannot drift apart.
 */
internal class Nana7miAccountSession {

    @Volatile
    var payload: Nana7miPayload? = null
        private set

    /**
     * Fetch one account. The server classifies it at 55 minutes; an expired account is refreshed
     * on the client and re-reported before it is returned to the search request.
     */
    suspend fun fetchReady(): Nana7miResult {
        val requesterUid = SessionManager.loggedInUid
        Timber.tag(LOG_TAG).d(
            "stage=fetch event=request requester_uid=%d",
            requesterUid,
        )
        val fetched = Client.pixshaft.fetchNana7mi(requesterUid)
        logFetchResult(requesterUid, fetched)
        val ready = when {
            fetched !is Nana7miResult.Success -> fetched
            !fetched.value.expired -> {
                Timber.tag(LOG_TAG).d(
                    "stage=expiry action=use_current account_uid=%d",
                    fetched.value.uid,
                )
                fetched
            }

            else -> renew(fetched.value, "server_expired")
        }
        payload = (ready as? Nana7miResult.Success)?.value
        return ready
    }

    /**
     * Execute a first or next page with the borrowed account. If Pixiv reports OAuth expiration,
     * refresh the borrowed account and replay this page exactly once.
     *
     * [successDetails] is deliberately supplied by the caller because illustration and novel
     * response models expose different list fields.
     */
    fun <T : Any> requestWithRefresh(
        initial: Nana7miPayload,
        stage: String,
        successDetails: (T) -> String,
        request: (String) -> Observable<T>,
    ): Observable<T> = Observable.defer {
        var activePayload = initial
        var refreshedForThisPage = false

        fun execute(current: Nana7miPayload): Observable<T> =
            request("Bearer ${current.account.access_token}")

        fun renewAndExecute(reason: String): Observable<T> =
            Observable.fromCallable {
                runBlocking { renew(activePayload, reason) }
            }.flatMap { renewed ->
                val current = (renewed as? Nana7miResult.Success)?.value
                if (current == null) {
                    val cause = (renewed as? Nana7miResult.InvalidResponse)?.cause
                        ?: IllegalStateException("nana7mi refresh failed")
                    Observable.error(BorrowedAccountUnavailableException(cause))
                } else {
                    refreshedForThisPage = true
                    activePayload = current
                    payload = current
                    execute(current)
                }
            }

        val firstAttempt = if (initial.expiresAt <= System.currentTimeMillis()) {
            Timber.tag(LOG_TAG).d(
                "stage=%s event=local_55m_expired account_uid=%d action=refresh_before_request",
                stage,
                initial.uid,
            )
            renewAndExecute("client_55m_expired")
        } else {
            execute(initial)
        }

        firstAttempt
            .onErrorResumeNext { error: Throwable ->
                if (refreshedForThisPage || !isPixivOAuthExpired(error)) {
                    Observable.error(error)
                } else {
                    Timber.tag(LOG_TAG).w(
                        "stage=%s result=oauth_expired account_uid=%d action=refresh_and_retry",
                        stage,
                        activePayload.uid,
                    )
                    renewAndExecute("pixiv_oauth_400")
                }
            }
            .doOnNext { response ->
                Timber.tag(LOG_TAG).d(
                    "stage=%s result=success account_uid=%d %s",
                    stage,
                    payload?.uid ?: initial.uid,
                    successDetails(response),
                )
            }
            .doOnError { error ->
                Timber.tag(LOG_TAG).w(
                    error,
                    "stage=%s result=failure account_uid=%d error_type=%s",
                    stage,
                    payload?.uid ?: initial.uid,
                    error.javaClass.simpleName,
                )
            }
    }

    fun resultLabel(result: Nana7miResult): String = when (result) {
        is Nana7miResult.Success -> if (result.value.expired) "expired" else "success"
        Nana7miResult.NoAccount -> "no_account"
        is Nana7miResult.RateLimited -> "rate_limited"
        is Nana7miResult.HttpFailure -> "http_${result.status}"
        Nana7miResult.InvalidRequest -> "invalid_request"
        is Nana7miResult.NetworkFailure -> "network_failure"
        is Nana7miResult.InvalidResponse -> "invalid_response"
    }

    /** Refresh one borrowed account without touching the app's logged-in account. */
    private suspend fun renew(stale: Nana7miPayload, reason: String): Nana7miResult {
        Timber.tag(LOG_TAG).d(
            "stage=expiry action=renew account_uid=%d reason=%s expired_at=%d overdue_ms=%d",
            stale.uid,
            reason,
            stale.expiresAt,
            (System.currentTimeMillis() - stale.expiresAt).coerceAtLeast(0L),
        )
        val refreshToken = stale.account.refresh_token?.takeIf { it.isNotBlank() }
        if (refreshToken == null) {
            Timber.tag(LOG_TAG).e(
                "stage=validate result=failure account_uid=%d reason=missing_refresh_token",
                stale.uid,
            )
            return Nana7miResult.InvalidResponse(
                IllegalStateException("nana7mi refresh_token missing"),
            )
        }
        val uid = stale.account.user?.id?.takeIf { it > 0L }
        if (uid == null) {
            Timber.tag(LOG_TAG).e(
                "stage=validate result=failure payload_uid=%d reason=missing_account_uid",
                stale.uid,
            )
            return Nana7miResult.InvalidResponse(
                IllegalStateException("nana7mi account uid missing"),
            )
        }

        val oauth = try {
            Timber.tag(LOG_TAG).d(
                "stage=refresh event=request account_uid=%d reason=%s",
                uid,
                reason,
            )
            PixivLogin.refreshTokenBlocking(refreshToken)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            if (error is InvalidRefreshTokenException) {
                val persisted = withContext(NonCancellable) {
                    AccountOnlineReportOutbox.persistInvalidRefreshToken(uid, refreshToken)
                }
                Timber.tag(LOG_TAG).w(
                    error,
                    "stage=refresh result=invalid_refresh_token account_uid=%d reason=%s invalidation_queued=%s",
                    uid,
                    reason,
                    persisted,
                )
                return Nana7miResult.InvalidResponse(error)
            }
            Timber.tag(LOG_TAG).w(
                error,
                "stage=refresh result=failure account_uid=%d reason=%s error_type=%s",
                uid,
                reason,
                error.javaClass.simpleName,
            )
            return Nana7miResult.InvalidResponse(error)
        }

        Timber.tag(LOG_TAG).d(
            "stage=refresh result=success account_uid=%d reason=%s expires_in_seconds=%d",
            uid,
            reason,
            oauth.expiresIn,
        )
        val refreshedAccount = stale.account.copy(
            access_token = oauth.accessToken,
            refresh_token = oauth.refreshToken,
            expires_in = oauth.expiresIn,
        )
        val updatedAt = System.currentTimeMillis()
        val refreshedPayload = stale.copy(
            account = refreshedAccount,
            updatedAt = updatedAt,
            expiresAt = updatedAt + VALID_MS,
            expired = false,
        )

        // Persist before the cancellable network attempt. Once Pixiv rotates a refresh token,
        // returning Success is only safe if either the server or this global outbox owns the new one.
        val persisted = withContext(NonCancellable) {
            AccountOnlineReportOutbox.persistOnline(uid, refreshedAccount)
        }
        if (!persisted) {
            Timber.tag(LOG_TAG).e(
                "stage=online_report result=persist_failed account_uid=%d reason=%s",
                uid,
                reason,
            )
            return Nana7miResult.InvalidResponse(
                IllegalStateException("refreshed AccountResponse could not be persisted"),
            )
        }
        Timber.tag(LOG_TAG).d(
            "stage=online_report event=request account_uid=%d reason=%s",
            uid,
            reason,
        )
        val reportResult = AccountOnlineReportOutbox.attemptOnline(uid)
        Timber.tag(LOG_TAG).d(
            "stage=online_report result=%s account_uid=%d reason=%s",
            reportResult.name.lowercase(),
            uid,
            reason,
        )
        Timber.tag(LOG_TAG).d(
            "stage=flow result=ready account_uid=%d updated_at=%d expires_at=%d",
            uid,
            refreshedPayload.updatedAt,
            refreshedPayload.expiresAt,
        )
        return Nana7miResult.Success(refreshedPayload)
    }

    private fun isPixivOAuthExpired(error: Throwable): Boolean {
        val http = generateSequence(error) { it.cause }
            .filterIsInstance<HttpException>()
            .firstOrNull()
            ?: return false
        if (http.code() != 400) return false
        val body = runCatching { http.response()?.errorBody()?.string().orEmpty() }
            .getOrDefault("")
        return body.contains(PIXIV_OAUTH_ERROR) || body.contains(PIXIV_INVALID_REFRESH_TOKEN)
    }

    private fun logFetchResult(requesterUid: Long, result: Nana7miResult) {
        val logger = Timber.tag(LOG_TAG)
        when (result) {
            is Nana7miResult.Success -> {
                val now = System.currentTimeMillis()
                logger.d(
                    "stage=fetch result=success requester_uid=%d account_uid=%d expired=%s updated_at=%d expires_at=%d age_ms=%d remaining_ms=%d",
                    requesterUid,
                    result.value.uid,
                    result.value.expired,
                    result.value.updatedAt,
                    result.value.expiresAt,
                    (now - result.value.updatedAt).coerceAtLeast(0L),
                    (result.value.expiresAt - now).coerceAtLeast(0L),
                )
            }

            Nana7miResult.NoAccount -> logger.w(
                "stage=fetch result=no_account requester_uid=%d",
                requesterUid,
            )

            is Nana7miResult.RateLimited -> logger.w(
                "stage=fetch result=rate_limited requester_uid=%d retry_after_seconds=%s",
                requesterUid,
                result.retryAfterSeconds?.toString() ?: "null",
            )

            is Nana7miResult.HttpFailure -> logger.w(
                "stage=fetch result=http_failure requester_uid=%d status=%d",
                requesterUid,
                result.status,
            )

            Nana7miResult.InvalidRequest -> logger.w(
                "stage=fetch result=invalid_request requester_uid=%d",
                requesterUid,
            )

            is Nana7miResult.NetworkFailure -> logger.w(
                result.cause,
                "stage=fetch result=network_failure requester_uid=%d error_type=%s",
                requesterUid,
                result.cause.javaClass.simpleName,
            )

            is Nana7miResult.InvalidResponse -> logger.w(
                result.cause,
                "stage=fetch result=invalid_response requester_uid=%d error_type=%s",
                requesterUid,
                result.cause?.javaClass?.simpleName ?: "unknown",
            )
        }
    }

    private companion object {
        const val LOG_TAG = "sadadsdasdw2"
        const val VALID_MS = 55L * 60_000L
        const val PIXIV_OAUTH_ERROR = "Error occurred at the OAuth process"
        const val PIXIV_INVALID_REFRESH_TOKEN = "Invalid refresh token"
    }
}

/** Only this error is eligible for a first-page fallback to the preview endpoint. */
internal class BorrowedAccountUnavailableException(cause: Throwable) : IOException(
    "borrowed Pixiv account could not be renewed",
    cause,
)

internal fun isBorrowedAccountUnavailable(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is BorrowedAccountUnavailableException }

/**
 * One fair process-wide permit around fetch/refresh/report/search. It covers first pages and
 * pagination from both illustration and novel tabs, so a quick tab switch queues rather than
 * running two borrowed-account flows concurrently.
 */
internal object Nana7miSearchSerial {
    private val semaphore = Semaphore(1, true)

    fun <T : Any> run(stage: String, source: () -> Observable<T>): Observable<T> =
        Observable.using(
            Callable {
                val startedAt = System.nanoTime()
                semaphore.acquire()
                Timber.tag(LOG_TAG).d(
                    "stage=serial event=acquired flow=%s waited_ms=%d",
                    stage,
                    (System.nanoTime() - startedAt) / 1_000_000L,
                )
                Permit(stage)
            },
            io.reactivex.functions.Function<Permit, Observable<T>> { source() },
            io.reactivex.functions.Consumer<Permit> { it.release() },
            true,
        )

    private class Permit(private val stage: String) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            semaphore.release()
            Timber.tag(LOG_TAG).d("stage=serial event=released flow=%s", stage)
        }
    }

    private const val LOG_TAG = "sadadsdasdw2"
}
