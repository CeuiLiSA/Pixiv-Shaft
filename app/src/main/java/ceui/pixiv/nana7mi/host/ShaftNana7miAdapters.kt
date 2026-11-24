package ceui.pixiv.nana7mi.host

import ceui.lisa.BuildConfig
import ceui.lisa.repo.Nana7miSearchCache
import ceui.loxia.User
import ceui.pixiv.actions.AccountOnlineReportOutbox
import ceui.pixiv.actions.Nana7miSearchTelemetry
import ceui.pixiv.actions.observeFirstOrRun
import ceui.pixiv.actions.trackOrRun
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.login.InvalidRefreshTokenException
import ceui.pixiv.login.PixivLogin
import ceui.pixiv.nana7mi.session.AccessDecision
import ceui.pixiv.nana7mi.session.AccountDispatcher
import ceui.pixiv.nana7mi.session.AccountPayloadUpdater
import ceui.pixiv.nana7mi.session.AccountReportStore
import ceui.pixiv.nana7mi.session.AccountTokenRefresher
import ceui.pixiv.nana7mi.session.AcquireResult
import ceui.pixiv.nana7mi.session.AuthFailureClassifier
import ceui.pixiv.nana7mi.session.BorrowSource
import ceui.pixiv.nana7mi.session.BorrowedAccount
import ceui.pixiv.nana7mi.session.BorrowedPageCache
import ceui.pixiv.nana7mi.session.DispatchRequest
import ceui.pixiv.nana7mi.session.FallbackReason
import ceui.pixiv.nana7mi.session.FetchEventOutcome
import ceui.pixiv.nana7mi.session.FirstPageHooks
import ceui.pixiv.nana7mi.session.MembershipPolicy
import ceui.pixiv.nana7mi.session.NextPageHooks
import ceui.pixiv.nana7mi.session.RateLimitInfo
import ceui.pixiv.nana7mi.session.RefreshedTokens
import ceui.pixiv.nana7mi.session.RenewalReason
import ceui.pixiv.nana7mi.session.ReportDeliveryOutcome
import ceui.pixiv.nana7mi.session.SessionAccessPolicy
import ceui.pixiv.nana7mi.session.SessionEvent
import ceui.pixiv.nana7mi.session.SessionObserver
import ceui.pixiv.nana7mi.session.TokenRefreshOutcome
import ceui.pixiv.shaftapi.Nana7miPayload
import ceui.pixiv.shaftapi.Nana7miResult
import ceui.pixiv.shaftapi.fetchNana7mi
import ceui.pixiv.ui.usage.Nana7miQuotaNotice
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import timber.log.Timber

/**
 * Identifies which Pixiv-Shaft build variant initiated a borrowed-account request.
 *
 * All Shaft variants associate with [BorrowSource.APP_SHAFT] (`"shaft"`):
 * `shaft-github-debug`, `shaft-github-release`, `shaft-google-debug`, `shaft-google-release`.
 */
internal object ShaftBorrowSource {
    val current: BorrowSource = forBuild(
        channel = BuildConfig.UPDATE_CHANNEL,
        isDebug = BuildConfig.IS_DEBUG_MODE,
    )

    fun forBuild(channel: String, isDebug: Boolean): BorrowSource =
        BorrowSource.shaft(
            channel = channel,
            buildType = if (isDebug) "debug" else "release",
        )
}

/** Defense-in-depth gate: Lite (Google Play) builds never borrow accounts or hit dispatch. */
internal object ShaftSessionAccessPolicy : SessionAccessPolicy {
    const val DISABLED_FOR_LITE_REASON = "disabled_for_lite"

    override fun evaluate(): AccessDecision =
        if (BuildConfig.IS_LITE) AccessDecision.Denied(DISABLED_FOR_LITE_REASON)
        else AccessDecision.Allowed
}

/** Maps [PixshaftApi.fetchNana7mi] wire results into pure-JVM [AcquireResult]. */
internal class PixshaftAccountDispatcher(
    private val includeSourceInBody: () -> Boolean = { false },
    private val fetch: suspend (request: DispatchRequest, includeSourceInBody: Boolean) -> Nana7miResult =
        { request, includeBody ->
            Client.pixshaft.fetchNana7mi(
                uid = request.requesterUid,
                requestId = request.requestId,
                source = request.source.id,
                app = request.source.app,
                includeSourceInBody = includeBody,
            )
        },
) : AccountDispatcher<AccountResponse> {
    override suspend fun dispatch(request: DispatchRequest): AcquireResult<AccountResponse> =
        fetch(request, includeSourceInBody()).toAcquireResult()
}

internal fun Nana7miPayload.toBorrowedAccount(): BorrowedAccount<AccountResponse> =
    BorrowedAccount.create(account) {
        uid = account.user?.id ?: 0L
        accessToken = account.access_token.orEmpty()
        refreshToken = account.refresh_token
        updatedAtMs = updatedAt
        expiresAtMs = expiresAt
        expired = this@toBorrowedAccount.expired
        isPremium = account.user?.is_premium
    }

internal fun Nana7miResult.toAcquireResult(): AcquireResult<AccountResponse> = when (this) {
    is Nana7miResult.Success -> AcquireResult.Success(value.toBorrowedAccount())
    Nana7miResult.DisabledForLite -> AcquireResult.Disabled(ShaftSessionAccessPolicy.DISABLED_FOR_LITE_REASON)
    Nana7miResult.NoAccount -> AcquireResult.NoAccount
    is Nana7miResult.NotPremium -> AcquireResult.NotPremium(uid = uid, payload = account)
    is Nana7miResult.RateLimited -> AcquireResult.RateLimited(
        RateLimitInfo.create {
            retryAfterSeconds = this@toAcquireResult.retryAfterSeconds
            scope = this@toAcquireResult.scope
            resetsAtMs = resetsAt
            serverTimeMs = serverTime
        },
    )
    is Nana7miResult.HttpFailure -> AcquireResult.HttpFailure(status)
    Nana7miResult.InvalidRequest -> AcquireResult.InvalidRequest
    is Nana7miResult.NetworkFailure -> AcquireResult.NetworkFailure(cause)
    is Nana7miResult.InvalidResponse -> AcquireResult.InvalidResponse(cause)
}

/** Refreshes a borrowed Pixiv account without touching the app's logged-in [SessionManager]. */
internal class PixivOAuthTokenRefresher(
    private val refreshBlocking: (String) -> ceui.pixiv.login.PixivOAuthResponse =
        PixivLogin::refreshTokenBlocking,
) : AccountTokenRefresher {
    override suspend fun refresh(uid: Long, refreshToken: String): TokenRefreshOutcome = try {
        val oauth = refreshBlocking(refreshToken)
        TokenRefreshOutcome.Success(
            RefreshedTokens.create {
                accessToken = oauth.accessToken
                this.refreshToken = oauth.refreshToken
                expiresInSeconds = oauth.expiresIn
                reportedUid = oauth.user?.id
                reportedIsPremium = oauth.user?.isPremium
            },
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (invalid: InvalidRefreshTokenException) {
        TokenRefreshOutcome.InvalidRefreshToken(invalid)
    } catch (error: Exception) {
        TokenRefreshOutcome.Failed(error)
    }
}

/**
 * Folds rotated OAuth tokens and attributed membership into [AccountResponse].
 *
 * Only `is_premium` is touched on [User]: Pixiv's token-response user is narrower than [User],
 * so replacing it wholesale would discard richer metadata already held by the server.
 */
internal object PixivAccountPayloadUpdater : AccountPayloadUpdater<AccountResponse> {
    override fun applyRefresh(
        previous: AccountResponse,
        tokens: RefreshedTokens,
        attributedPremium: Boolean?,
    ): AccountResponse = previous.copy(
        access_token = tokens.accessToken,
        refresh_token = tokens.refreshToken,
        expires_in = tokens.expiresInSeconds,
        user = mergeUserMembership(previous.user, attributedPremium),
    )

    internal fun mergeUserMembership(stored: User?, attributedPremium: Boolean?): User? {
        if (stored == null) return null
        val merged = MembershipPolicy.merge(stored.is_premium, attributedPremium)
        return if (merged == stored.is_premium) stored else stored.copy(is_premium = merged)
    }
}

/** Adapts the process-wide [AccountOnlineReportOutbox] to [AccountReportStore]. */
internal class OutboxAccountReportStore(
    private val outbox: AccountOnlineReportOutbox,
) : AccountReportStore<AccountResponse> {
    override suspend fun persistOnline(uid: Long, payload: AccountResponse): Boolean =
        outbox.persistOnline(uid, payload)

    override suspend fun attemptOnline(uid: Long): ReportDeliveryOutcome =
        when (outbox.attemptOnline(uid)) {
            AccountOnlineReportOutbox.PersistResult.DELIVERED -> ReportDeliveryOutcome.DELIVERED
            AccountOnlineReportOutbox.PersistResult.QUEUED -> ReportDeliveryOutcome.QUEUED
            AccountOnlineReportOutbox.PersistResult.FAILED -> ReportDeliveryOutcome.FAILED
        }

    override suspend fun persistInvalidRefreshToken(uid: Long, refreshToken: String): Boolean =
        outbox.persistInvalidRefreshToken(uid, refreshToken)
}

/** Recognizes Pixiv's HTTP 400 OAuth expiration responses without pulling Retrofit into `:nana7mi`. */
internal object PixivOAuthFailureClassifier : AuthFailureClassifier {
    private const val PIXIV_OAUTH_ERROR = "Error occurred at the OAuth process"
    private const val PIXIV_INVALID_REFRESH_TOKEN = "Invalid refresh token"

    override fun isAccessTokenExpired(error: Throwable): Boolean {
        val http = generateSequence(error) { current ->
            current.cause?.takeUnless { it === current }
        }
            .filterIsInstance<HttpException>()
            .firstOrNull()
            ?: return false
        if (http.code() != 400) return false
        val body = runCatching { http.response()?.errorBody()?.string().orEmpty() }
            .getOrDefault("")
        return body.contains(PIXIV_OAUTH_ERROR) || body.contains(PIXIV_INVALID_REFRESH_TOKEN)
    }
}

/** Forwards quota rejections from the session engine to the UI notice channel. */
internal object QuotaNoticeSessionObserver : SessionObserver {
    override fun onEvent(event: SessionEvent) {
        if (event is SessionEvent.FetchCompleted) {
            val outcome = event.outcome
            if (outcome is FetchEventOutcome.RateLimited) {
                Nana7miQuotaNotice.report(outcome.info)
            }
        }
    }
}

/** Emits structured Timber diagnostics preserving the historical Nana7mi log tags and format. */
internal object TimberSessionObserver : SessionObserver {
    const val SESSION_LOG_TAG = "sadadsdasdw2"
    const val RETRY_LOG_TAG = "Nana7miRetry"

    private fun RenewalReason.toLogReason(): String = when (this) {
        RenewalReason.SERVER_EXPIRED -> "server_expired"
        RenewalReason.LOCAL_EXPIRY -> "client_55m_expired"
        RenewalReason.AUTH_REJECTED -> "pixiv_oauth_400"
    }

    override fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.SerialPermitAcquired -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=serial event=acquired flow=%s waited_ms=%d",
                event.stage,
                event.waitedMs,
            )
            is SessionEvent.SerialPermitReleased -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=serial event=released flow=%s",
                event.stage,
            )
            is SessionEvent.RetryScheduled -> Timber.tag(RETRY_LOG_TAG).w(
                "stage=%s result=%s action=retry attempt=%d remaining_ms=%d",
                event.stage,
                event.failureKind.wire,
                event.attempt,
                event.remainingBudgetMs,
            )
            is SessionEvent.FetchStarted -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=fetch event=request requester_uid=%d source=%s app=%s",
                event.requesterUid,
                event.source.id,
                event.source.app,
            )
            is SessionEvent.FetchCompleted -> logFetchOutcome(event.requesterUid, event.source, event.outcome)
            is SessionEvent.PaidRequestIdRejected -> Timber.tag(SESSION_LOG_TAG).w(
                "stage=fetch result=paid_request_id_rejected source=%s app=%s action=retry_fresh_search",
                event.source.id,
                event.source.app,
            )
            is SessionEvent.AccountReadyCurrent -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=expiry action=use_current account_uid=%d",
                event.accountUid,
            )
            is SessionEvent.RenewalStarted -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=expiry action=renew account_uid=%d reason=%s expired_at=%d overdue_ms=%d",
                event.accountUid,
                event.reason.toLogReason(),
                event.expiredAtMs,
                event.overdueMs,
            )
            is SessionEvent.RenewalValidationFailed -> Timber.tag(SESSION_LOG_TAG).e(
                "stage=validate result=failure account_uid=%d reason=%s",
                event.accountUid,
                event.reason.wire,
            )
            is SessionEvent.TokenRefreshStarted -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=refresh event=request account_uid=%d reason=%s",
                event.accountUid,
                event.reason.toLogReason(),
            )
            is SessionEvent.TokenRefreshSucceeded -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=refresh result=success account_uid=%d reason=%s expires_in_seconds=%d",
                event.accountUid,
                event.reason.toLogReason(),
                event.expiresInSeconds,
            )
            is SessionEvent.TokenRefreshInvalidGrant -> Timber.tag(SESSION_LOG_TAG).w(
                event.cause,
                "stage=refresh result=invalid_refresh_token account_uid=%d reason=%s invalidation_queued=%s",
                event.accountUid,
                event.reason.toLogReason(),
                event.invalidationQueued,
            )
            is SessionEvent.TokenRefreshFailed -> Timber.tag(SESSION_LOG_TAG).w(
                event.cause,
                "stage=refresh result=failure account_uid=%d reason=%s error_type=%s",
                event.accountUid,
                event.reason.toLogReason(),
                event.cause.javaClass.simpleName,
            )
            is SessionEvent.OnlineReportPersistFailed -> Timber.tag(SESSION_LOG_TAG).e(
                "stage=online_report result=persist_failed account_uid=%d reason=%s",
                event.accountUid,
                event.reason.toLogReason(),
            )
            is SessionEvent.OnlineReportStarted -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=online_report event=request account_uid=%d reason=%s",
                event.accountUid,
                event.reason.toLogReason(),
            )
            is SessionEvent.OnlineReportCompleted -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=online_report result=%s account_uid=%d reason=%s",
                event.outcome.name.lowercase(),
                event.accountUid,
                event.reason.toLogReason(),
            )
            is SessionEvent.NotPremiumDetected -> Timber.tag(SESSION_LOG_TAG).w(
                "stage=premium result=not_premium account_uid=%d source=%s report=%s",
                event.accountUid,
                event.renewalReason?.toLogReason() ?: "fetch",
                event.reportOutcome,
            )
            is SessionEvent.RenewalSucceeded -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=expiry result=renewed account_uid=%d updated_at=%d expires_at=%d",
                event.accountUid,
                event.updatedAtMs,
                event.expiresAtMs,
            )
            is SessionEvent.LocalExpiryRefreshTriggered -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=%s event=local_55m_expired account_uid=%d action=refresh_before_request",
                event.stage,
                event.accountUid,
            )
            is SessionEvent.AuthRejectedRetryTriggered -> Timber.tag(SESSION_LOG_TAG).w(
                "stage=%s result=oauth_expired account_uid=%d action=refresh_and_retry",
                event.stage,
                event.accountUid,
            )
            is SessionEvent.RequestSucceeded -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=%s result=success account_uid=%d %s",
                event.stage,
                event.accountUid,
                event.details.orEmpty(),
            )
            is SessionEvent.RequestFailed -> Timber.tag(SESSION_LOG_TAG).w(
                event.cause,
                "stage=%s result=failure account_uid=%d error_type=%s",
                event.stage,
                event.accountUid,
                event.cause.javaClass.simpleName,
            )
            is SessionEvent.CachedCursorBorrowStarted -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=%s event=borrow_for_cached_cursor source=%s app=%s",
                event.stage,
                event.source.id,
                event.source.app,
            )
            is SessionEvent.BorrowedRequestStarted -> Timber.tag(SESSION_LOG_TAG).d(
                "stage=%s event=request account_uid=%d",
                event.stage,
                event.accountUid,
            )
            is SessionEvent.PaginationEnded -> Timber.tag(SESSION_LOG_TAG).w(
                "stage=%s result=borrow_lost reason=%s action=end_pagination",
                event.stage,
                event.reason.wire,
            )
            is SessionEvent.CacheOperationFailed -> Timber.tag(SESSION_LOG_TAG).w(
                event.cause,
                "stage=%s cache_%s=error error_type=%s",
                event.stage,
                event.operation.wire,
                event.cause.javaClass.simpleName,
            )
            SessionEvent.EngineClosed -> Timber.tag(SESSION_LOG_TAG).d("stage=engine event=closed")
        }
    }

    private fun logFetchOutcome(requesterUid: Long, source: BorrowSource, outcome: FetchEventOutcome) {
        val logger = Timber.tag(SESSION_LOG_TAG)
        when (outcome) {
            is FetchEventOutcome.Success -> logger.d(
                "stage=fetch result=success requester_uid=%d account_uid=%d source=%s app=%s expired=%s updated_at=%d expires_at=%d age_ms=%d remaining_ms=%d",
                requesterUid,
                outcome.accountUid,
                source.id,
                source.app,
                outcome.expired,
                outcome.updatedAtMs,
                outcome.expiresAtMs,
                outcome.ageMs,
                outcome.remainingMs,
            )
            is FetchEventOutcome.Disabled -> logger.d(
                "stage=fetch result=%s requester_uid=%d source=%s app=%s",
                outcome.reason,
                requesterUid,
                source.id,
                source.app,
            )
            FetchEventOutcome.Closed -> logger.d(
                "stage=fetch result=engine_closed requester_uid=%d source=%s app=%s",
                requesterUid,
                source.id,
                source.app,
            )
            FetchEventOutcome.NoAccount -> logger.w(
                "stage=fetch result=no_account requester_uid=%d source=%s app=%s",
                requesterUid,
                source.id,
                source.app,
            )
            is FetchEventOutcome.NotPremium -> logger.w(
                "stage=fetch result=not_premium requester_uid=%d account_uid=%d source=%s app=%s",
                requesterUid,
                outcome.accountUid,
                source.id,
                source.app,
            )
            is FetchEventOutcome.RateLimited -> logger.w(
                "stage=fetch result=rate_limited requester_uid=%d source=%s app=%s retry_after_seconds=%s",
                requesterUid,
                source.id,
                source.app,
                outcome.info.retryAfterSeconds?.toString() ?: "null",
            )
            is FetchEventOutcome.HttpFailure -> logger.w(
                "stage=fetch result=http_failure requester_uid=%d source=%s app=%s status=%d",
                requesterUid,
                source.id,
                source.app,
                outcome.statusCode,
            )
            FetchEventOutcome.InvalidRequest -> logger.w(
                "stage=fetch result=invalid_request requester_uid=%d source=%s app=%s",
                requesterUid,
                source.id,
                source.app,
            )
            is FetchEventOutcome.NetworkFailure -> logger.w(
                outcome.cause,
                "stage=fetch result=network_failure requester_uid=%d source=%s app=%s error_type=%s",
                requesterUid,
                source.id,
                source.app,
                outcome.cause.javaClass.simpleName,
            )
            is FetchEventOutcome.InvalidResponse -> logger.w(
                outcome.cause,
                "stage=fetch result=invalid_response requester_uid=%d source=%s app=%s error_type=%s",
                requesterUid,
                source.id,
                source.app,
                outcome.cause?.javaClass?.simpleName ?: "unknown",
            )
        }
    }
}

/** Bridges [Nana7miSearchCache] to the pure-JVM [BorrowedPageCache] port. */
internal class SearchCachePageAdapter<T : Any>(
    private val kind: Nana7miSearchCache.Kind,
    private val key: String,
    private val page: Nana7miSearchCache.Page,
    private val maxAgeMs: Long,
    private val type: Class<T>,
    private val stage: String,
    private val includeSourceInBody: Boolean = false,
) : BorrowedPageCache<T> {
    override suspend fun lookup(requestId: String?, source: BorrowSource): T? =
        Nana7miSearchCache.lookup(
            kind = kind,
            key = key,
            page = page,
            requestId = requestId,
            maxAgeMs = maxAgeMs,
            type = type,
            stage = stage,
            source = source,
            includeSourceInBody = includeSourceInBody,
        )

    override fun store(page: T) {
        Nana7miSearchCache.store(kind, key, page, stage)
    }
}

/** Bridges [Nana7miSearchTelemetry.Flow] to [FirstPageHooks] and [NextPageHooks]. */
internal object Nana7miTelemetryFlowHooks {

    fun firstPage(
        telemetry: Nana7miSearchTelemetry.Flow?,
        onBorrowed: ((Long) -> Unit)? = null,
    ): FirstPageHooks = object : FirstPageHooks {
        override suspend fun <T : Any> aroundFlow(block: suspend () -> T): T =
            telemetry.observeFirstOrRun(block)

        override fun onCacheHit(requestId: String?) {
            telemetry?.cacheHit()
        }

        override suspend fun <T : Any> aroundCacheHit(
            requestId: String?,
            block: suspend () -> T,
        ): T = telemetry.trackOrRun(
            page = Nana7miSearchTelemetry.Page.FIRST,
            route = Nana7miSearchTelemetry.Route.CACHE_HIT,
            borrowedUid = null,
            reason = null,
            eventId = requestId,
            source = block,
        )

        override fun onAccountBorrowed(accountUid: Long) {
            telemetry?.borrowed(accountUid)
            onBorrowed?.invoke(accountUid)
        }

        override suspend fun <T : Any> aroundBorrowedRequest(
            accountUid: Long,
            requestId: String?,
            block: suspend () -> T,
        ): T = telemetry.trackOrRun(
            page = Nana7miSearchTelemetry.Page.FIRST,
            route = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
            borrowedUid = accountUid,
            eventId = requestId,
            source = block,
        )

        override fun onFallback(reason: FallbackReason) {
            telemetry?.fallback(reason.code)
        }

        override suspend fun <T : Any> aroundFallbackRequest(
            reason: FallbackReason,
            block: suspend () -> T,
        ): T = telemetry.trackOrRun(
            page = Nana7miSearchTelemetry.Page.FIRST,
            source = block,
        )
    }

    fun nextPage(
        telemetry: Nana7miSearchTelemetry.Flow?,
    ): NextPageHooks = object : NextPageHooks {
        override suspend fun <T : Any> aroundDirectRequest(block: suspend () -> T): T =
            telemetry.trackOrRun(
                page = Nana7miSearchTelemetry.Page.NEXT,
                source = block,
            )

        override suspend fun <T : Any> aroundCacheHit(
            requestId: String?,
            block: suspend () -> T,
        ): T = telemetry.trackOrRun(
            page = Nana7miSearchTelemetry.Page.NEXT,
            route = Nana7miSearchTelemetry.Route.CACHE_HIT,
            borrowedUid = null,
            reason = null,
            eventId = requestId,
            source = block,
        )

        override suspend fun <T : Any> aroundBorrowedRequest(
            accountUid: Long,
            requestId: String?,
            block: suspend () -> T,
        ): T = telemetry.trackOrRun(
            page = Nana7miSearchTelemetry.Page.NEXT,
            route = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
            borrowedUid = accountUid,
            eventId = requestId,
            source = block,
        )
    }
}
