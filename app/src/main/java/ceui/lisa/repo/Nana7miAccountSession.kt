package ceui.lisa.repo

import ceui.lisa.BuildConfig
import ceui.loxia.Client
import ceui.loxia.User
import ceui.loxia.Nana7miPayload
import ceui.loxia.Nana7miResult
import ceui.loxia.fetchNana7mi
import ceui.pixiv.ui.usage.Nana7miQuotaNotice
import ceui.pixiv.actions.AccountOnlineReportOutbox
import ceui.pixiv.login.InvalidRefreshTokenException
import ceui.pixiv.login.PixivLogin
import ceui.pixiv.login.PixivOAuthUser
import ceui.pixiv.session.SessionManager
import io.reactivex.Observable
import io.reactivex.exceptions.Exceptions
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
internal class Nana7miAccountSession(
    /** Durable outbox for the refreshed-token / invalid-token reports this session emits. */
    private val outbox: AccountOnlineReportOutbox,
) {

    @Volatile
    var payload: Nana7miPayload? = null
        private set

    /**
     * True once a borrowed account backed this session's pagination and then became unusable.
     *
     * [payload] alone cannot express this: it is also null when nothing was ever borrowed (plain
     * search, direct preview, or a first-page fallback), and in *those* cases the cursor belongs to
     * the logged-in account and may be continued normally. Only here does the cursor point at a
     * premium-only search that the logged-in account must never be used to continue.
     *
     * Reset comes for free: every [initApi] builds a fresh session.
     */
    @Volatile
    var borrowedAccountLost: Boolean = false
        private set

    /**
     * True when this session's first page came out of the pixshaft search cache: the cursor is a
     * premium-only one, but nothing has been borrowed yet. A page turn that misses the cache must
     * borrow *then* — and must never continue the cursor with the logged-in account.
     *
     * Lives on the session, not the repository, for the same reason [payload] does: a lookup that
     * completes late can only mark the session it belongs to, never the search that replaced it.
     */
    @Volatile
    var cursorFromCache: Boolean = false
        private set

    /** 已付费首屏的幂等 ID；缓存游标翻页未命中时用它换账号，不再开启第二次搜索。 */
    @Volatile
    var cachedFirstRequestId: String? = null
        private set

    /** true 表示该缓存首屏已按新协议计费，后续账号交接绝不能丢掉它的 ID。 */
    @Volatile
    var cachedFirstRequestIdRequired: Boolean = false
        private set

    fun markCursorFromCache(requestId: String?, requestIdProtocolEnabled: Boolean) {
        require(!requestIdProtocolEnabled || requestId != null) {
            "request-id protocol enabled without a paid first-page id"
        }
        cursorFromCache = true
        cachedFirstRequestId = requestId
        cachedFirstRequestIdRequired = requestIdProtocolEnabled
    }

    /**
     * Fetch one account. The server classifies it at 55 minutes; an expired account is refreshed
     * on the client and re-reported before it is returned to the search request.
     */
    suspend fun fetchReady(requestId: String? = null): Nana7miResult {
        // Defense in depth: repository routing already disables borrowing in Lite, but keeping the
        // network boundary closed prevents a future caller from accidentally bypassing that gate.
        if (BuildConfig.IS_LITE) return Nana7miResult.DisabledForLite
        val requesterUid = SessionManager.loggedInUid
        Timber.tag(LOG_TAG).d(
            "stage=fetch event=request requester_uid=%d",
            requesterUid,
        )
        val fetched = Client.pixshaft.fetchNana7mi(requesterUid, requestId)
        logFetchResult(requesterUid, fetched)
        if (fetched is Nana7miResult.RateLimited) {
            // 配额拒绝对用户是可见事实（结果会静默降级成热度预览），限流拒绝不是。
            // 这里是插画和小说两个搜索仓库共用的唯一借号入口，报在这里就不用各报一遍。
            Nana7miQuotaNotice.report(fetched)
        }
        if (fetched is Nana7miResult.NotPremium) {
            // 服务端派发池只收 is_premium = 1，走到这里说明那一行的分类和 blob 对不上。把这份
            // AccountResponse 原样报回去，saveOnline 会按 blob 里的真实会员状态重新分类并停止派发。
            reportNotPremium(fetched, "fetch")
        }
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
        lease: Nana7miSearchLease,
        successDetails: (T) -> String,
        request: (String) -> Observable<T>,
    ): Observable<T> = Observable.defer {
        var activePayload = initial
        var refreshedForThisPage = false

        fun execute(current: Nana7miPayload): Observable<T> =
            request("Bearer ${current.account.access_token}")

        fun renewAndExecute(reason: String): Observable<T> =
            lease.blockingObservable {
                runBlocking { renew(activePayload, reason) }
            }.flatMap { renewed ->
                val current = (renewed as? Nana7miResult.Success)?.value
                if (current == null) {
                    // The caller may fall back to an unauthenticated preview page. Do not leave
                    // pagination attached to the borrowed account whose renewal just failed.
                    payload = null
                    borrowedAccountLost = true
                    val cause = when (renewed) {
                        is Nana7miResult.InvalidResponse -> renewed.cause
                        // Losing premium mid-pagination is not a token failure, but it is just as
                        // terminal for this borrowed account. Report it as unavailable so a first
                        // page can still fall back to the preview endpoint.
                        is Nana7miResult.NotPremium ->
                            IllegalStateException("nana7mi account is no longer premium")
                        else -> null
                    } ?: IllegalStateException("nana7mi refresh failed")
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

    /**
     * Hand a lapsed borrowed account back to the server. The plain online report *is* the
     * notification: `saveOnline` derives membership from `user.is_premium` and pauses dispatch for
     * anything that is not premium, so no separate endpoint is involved.
     */
    private suspend fun reportNotPremium(result: Nana7miResult.NotPremium, stage: String) {
        val persisted = withContext(NonCancellable) {
            outbox.persistOnline(result.uid, result.account)
        }
        val reportResult = if (persisted) {
            outbox.attemptOnline(result.uid).name.lowercase()
        } else {
            "persist_failed"
        }
        Timber.tag(LOG_TAG).w(
            "stage=premium result=not_premium account_uid=%d source=%s report=%s",
            result.uid,
            stage,
            reportResult,
        )
    }

    fun resultLabel(result: Nana7miResult): String = when (result) {
        is Nana7miResult.Success -> if (result.value.expired) "expired" else "success"
        Nana7miResult.DisabledForLite -> "disabled_for_lite"
        Nana7miResult.NoAccount -> "no_account"
        is Nana7miResult.NotPremium -> "not_premium"
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
                    outbox.persistInvalidRefreshToken(uid, refreshToken)
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
        // Held rather than inlined: the NotPremium branch further down tests this same value,
        // and it must test `== false` (an explicit "not a member") rather than falsiness —
        // a null here means pixiv said nothing and the account must stay in the pool.
        val freshPremium = freshMembershipOf(oauth.user, uid)
        val refreshedAccount = stale.account.copy(
            access_token = oauth.accessToken,
            refresh_token = oauth.refreshToken,
            expires_in = oauth.expiresIn,
            user = mergeMembership(stored = stale.account.user, fresh = freshPremium),
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
            outbox.persistOnline(uid, refreshedAccount)
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
        val reportResult = outbox.attemptOnline(uid)
        Timber.tag(LOG_TAG).d(
            "stage=online_report result=%s account_uid=%d reason=%s",
            reportResult.name.lowercase(),
            uid,
            reason,
        )
        if (freshPremium == false) {
            // 上面那次 online 上报本身就是「通知服务端」：saveOnline 读 user.is_premium，非 true 一律
            // stmtPauseDispatch 移出派发池（borrow_count 保留，用户续费后再次上报会自动恢复）。
            // 刷新出来的新 token 也一并交出去了，所以这个号不会被我们刷坏，只是不再被借出。
            Timber.tag(LOG_TAG).w(
                "stage=premium result=not_premium account_uid=%d reason=%s report=%s",
                uid,
                reason,
                reportResult.name.lowercase(),
            )
            return Nana7miResult.NotPremium(uid, refreshedAccount)
        }
        // stage=expiry, not stage=flow: renew() also runs mid-pagination, and the illustration and
        // novel flows own their own stage names ("flow" / "novel_flow"). Closing here with a flow
        // event mislabeled every novel renewal and made "ready" show up in the middle of a page
        // request. This pairs with the "action=renew" line that opened the renewal instead.
        Timber.tag(LOG_TAG).d(
            "stage=expiry result=renewed account_uid=%d updated_at=%d expires_at=%d",
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

            Nana7miResult.DisabledForLite -> logger.d(
                "stage=fetch result=disabled_for_lite requester_uid=%d",
                requesterUid,
            )

            Nana7miResult.NoAccount -> logger.w(
                "stage=fetch result=no_account requester_uid=%d",
                requesterUid,
            )

            is Nana7miResult.NotPremium -> logger.w(
                "stage=fetch result=not_premium requester_uid=%d account_uid=%d",
                requesterUid,
                result.uid,
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

    fun <T : Any> run(
        stage: String,
        source: (Nana7miSearchLease) -> Observable<T>,
    ): Observable<T> =
        Observable.using(
            Callable {
                val startedAt = System.nanoTime()
                semaphore.acquire()
                Timber.tag(LOG_TAG).d(
                    "stage=serial event=acquired flow=%s waited_ms=%d",
                    stage,
                    (System.nanoTime() - startedAt) / 1_000_000L,
                )
                Nana7miSearchLease(stage, semaphore)
            },
            io.reactivex.functions.Function<Nana7miSearchLease, Observable<T>> { source(it) },
            io.reactivex.functions.Consumer<Nana7miSearchLease> { it.requestRelease() },
            false,
        )

    private const val LOG_TAG = "sadadsdasdw2"
}

/**
 * Keeps a serial permit alive while synchronous token work unwinds after Rx disposal.
 *
 * Disposing a subscribeOn task only interrupts its thread; a blocking OAuth call can ignore that
 * interrupt, and NonCancellable persistence deliberately finishes anyway. The using disposer marks
 * this lease for release, while [blockingObservable] performs the actual release after the running
 * block and its synchronous downstream hand-off have returned.
 */
internal class Nana7miSearchLease(
    private val stage: String,
    private val semaphore: Semaphore,
) {
    private val stateLock = Any()
    private var activeBlockingWork = 0
    private var releaseRequested = false
    private var released = false

    fun <T : Any> blockingObservable(block: () -> T): Observable<T> = Observable.create { emitter ->
        if (!beginBlockingWork()) {
            emitter.tryOnError(CancellationException("nana7mi serial lease already released"))
            return@create
        }
        try {
            val value = block()
            if (!emitter.isDisposed) {
                // onNext synchronously runs flatMap's mapper/subscription. Keep the lease active
                // until that hand-off returns so cancellation cannot release between refresh and
                // installing the rotated payload.
                emitter.onNext(value)
                if (!emitter.isDisposed) emitter.onComplete()
            }
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            if (!emitter.isDisposed) emitter.onError(error)
        } finally {
            endBlockingWork()
        }
    }

    private fun beginBlockingWork(): Boolean = synchronized(stateLock) {
        if (releaseRequested) return@synchronized false
        activeBlockingWork += 1
        true
    }

    private fun endBlockingWork() {
        val releaseNow = synchronized(stateLock) {
            check(activeBlockingWork > 0)
            activeBlockingWork -= 1
            markReleasedIfIdle()
        }
        if (releaseNow) releasePermit()
    }

    fun requestRelease() {
        val releaseNow = synchronized(stateLock) {
            releaseRequested = true
            markReleasedIfIdle()
        }
        if (releaseNow) releasePermit()
    }

    private fun markReleasedIfIdle(): Boolean {
        if (!releaseRequested || activeBlockingWork != 0 || released) return false
        released = true
        return true
    }

    private fun releasePermit() {
        semaphore.release()
        Timber.tag(LOG_TAG).d("stage=serial event=released flow=%s", stage)
    }

    private companion object {
        const val LOG_TAG = "sadadsdasdw2"
    }
}

/**
 * The membership a token refresh is allowed to speak for, or `null` when it
 * cannot speak for this account at all.
 *
 * `uid` is guaranteed `> 0` by the caller, and pixiv-login reports `0` when
 * pixiv omits or mangles the id, so the equality check doubles as a reject
 * for responses that cannot be tied to the account being renewed.
 */
internal fun freshMembershipOf(user: PixivOAuthUser?, uid: Long): Boolean? =
    user?.takeIf { it.id == uid }?.isPremium

/**
 * Folds a refresh response's membership into the stored account.
 *
 * Only `is_premium` is touched: pixiv's token-response user is narrower than
 * [User] (no pixiv_id / gender / comment), so replacing it wholesale would
 * shave off richer data the server already holds.
 *
 * [fresh] being `null` means pixiv said nothing about membership this time —
 * it omits the field on some refresh responses — and "said nothing" must not
 * be written down as "not a member", which would demote a paying account and
 * drop it out of the lending pool. `null` therefore keeps [stored] verbatim.
 */
internal fun mergeMembership(stored: User?, fresh: Boolean?): User? =
    if (fresh == null) stored else stored?.copy(is_premium = fresh)
