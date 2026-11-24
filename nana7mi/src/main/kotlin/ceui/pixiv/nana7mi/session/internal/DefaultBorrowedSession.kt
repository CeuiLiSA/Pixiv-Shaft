package ceui.pixiv.nana7mi.session.internal

import ceui.pixiv.nana7mi.session.AccessDecision
import ceui.pixiv.nana7mi.session.AcquireResult
import ceui.pixiv.nana7mi.session.BorrowedAccount
import ceui.pixiv.nana7mi.session.BorrowedAccountUnavailableException
import ceui.pixiv.nana7mi.session.BorrowedPageCache
import ceui.pixiv.nana7mi.session.BorrowedSession
import ceui.pixiv.nana7mi.session.BorrowedSessionState
import ceui.pixiv.nana7mi.session.CacheOperation
import ceui.pixiv.nana7mi.session.ExecutionLease
import ceui.pixiv.nana7mi.session.FallbackReason
import ceui.pixiv.nana7mi.session.FetchEventOutcome
import ceui.pixiv.nana7mi.session.FirstPageRequest
import ceui.pixiv.nana7mi.session.MembershipPolicy
import ceui.pixiv.nana7mi.session.Nana7miSessionConfig
import ceui.pixiv.nana7mi.session.NetworkRetryExecutor
import ceui.pixiv.nana7mi.session.NextPageRequest
import ceui.pixiv.nana7mi.session.PaginationEndReason
import ceui.pixiv.nana7mi.session.RenewalReason
import ceui.pixiv.nana7mi.session.ReportDeliveryOutcome
import ceui.pixiv.nana7mi.session.SerialExecutionCoordinator
import ceui.pixiv.nana7mi.session.SessionEvent
import ceui.pixiv.nana7mi.session.TokenRefreshOutcome
import ceui.pixiv.nana7mi.session.ValidationFailureReason
import ceui.pixiv.nana7mi.session.isBorrowedAccountUnavailable
import ceui.pixiv.nana7mi.session.isPaidRequestIdRejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal class DefaultBorrowedSession<A : Any>(
    private val config: Nana7miSessionConfig<A>,
    private val serialCoordinator: SerialExecutionCoordinator,
    private val retryExecutor: NetworkRetryExecutor,
    private val observer: SafeSessionObserver,
    private val isEngineClosed: () -> Boolean,
) : BorrowedSession<A> {

    private val _stateFlow: MutableStateFlow<BorrowedSessionState<A>> =
        MutableStateFlow(BorrowedSessionState.Idle)

    override val state: BorrowedSessionState<A>
        get() = _stateFlow.value

    override val stateFlow: StateFlow<BorrowedSessionState<A>> = _stateFlow.asStateFlow()

    override fun markCursorFromCache(paidRequestId: String?, requestIdRequired: Boolean) {
        val next = BorrowedSessionState.CachedCursor(
            paidRequestId = paidRequestId,
            requestIdRequired = requestIdRequired,
        )
        check(_stateFlow.compareAndSet(BorrowedSessionState.Idle, next)) {
            "markCursorFromCache is only valid from Idle state, current state is ${_stateFlow.value}"
        }
    }

    override suspend fun acquireReady(
        requesterUid: Long,
        requestId: String?,
    ): AcquireResult<A> {
        if (isEngineClosed()) {
            observer.emit(
                SessionEvent.FetchCompleted(
                    requesterUid = requesterUid,
                    outcome = FetchEventOutcome.Closed,
                ),
            )
            return AcquireResult.Closed
        }
        if (_stateFlow.value is BorrowedSessionState.Lost) {
            return AcquireResult.InvalidResponse(
                BorrowedAccountUnavailableException(
                    IllegalStateException("borrowed session already lost"),
                ),
            )
        }
        when (val decision = config.accessPolicy.evaluate()) {
            AccessDecision.Allowed -> Unit
            is AccessDecision.Denied -> {
                val denied = AcquireResult.Disabled(decision.reason)
                observer.emit(
                    SessionEvent.FetchCompleted(
                        requesterUid = requesterUid,
                        outcome = FetchEventOutcome.Disabled(decision.reason),
                    ),
                )
                return denied
            }
        }

        observer.emit(SessionEvent.FetchStarted(requesterUid = requesterUid))
        val fetched = if (requestId == null) {
            // Legacy backends cannot deduplicate quota consumption, so never replay dispatch.
            config.dispatcher.dispatch(requesterUid, null)
        } else {
            retryExecutor.executeResult(
                stage = STAGE_DISPATCH,
                failureOf = { (it as? AcquireResult.NetworkFailure)?.cause },
            ) {
                config.dispatcher.dispatch(requesterUid, requestId)
            }
        }
        observer.emit(
            SessionEvent.FetchCompleted(
                requesterUid = requesterUid,
                outcome = toFetchOutcome(fetched),
            ),
        )

        if (fetched is AcquireResult.NotPremium) {
            reportNotPremium(uid = fetched.uid, payload = fetched.payload)
        }

        val ready = when {
            fetched !is AcquireResult.Success -> fetched
            !fetched.account.expired -> {
                observer.emit(SessionEvent.AccountReadyCurrent(accountUid = fetched.account.uid))
                fetched
            }
            else -> renew(fetched.account, RenewalReason.SERVER_EXPIRED)
        }

        applyAcquireState(ready)
        return ready
    }

    override suspend fun acquireForCachedCursor(requesterUid: Long): AcquireResult<A> {
        val paidRequestId = when (val current = _stateFlow.value) {
            is BorrowedSessionState.Lost -> return AcquireResult.InvalidResponse(
                BorrowedAccountUnavailableException(
                    IllegalStateException("borrowed session already lost"),
                ),
            )
            is BorrowedSessionState.Active -> return AcquireResult.Success(current.account)
            is BorrowedSessionState.CachedCursor -> current.paidRequestId
            is BorrowedSessionState.Idle -> null
        }

        val first = acquireReady(requesterUid = requesterUid, requestId = paidRequestId)
        if (paidRequestId == null || !first.isPaidRequestIdRejected()) return first
        observer.emit(SessionEvent.PaidRequestIdRejected(requesterUid = requesterUid))
        return acquireReady(
            requesterUid = requesterUid,
            requestId = config.requestIdGenerator.nextId(),
        )
    }

    override suspend fun <T : Any> executeWithRefresh(
        initial: BorrowedAccount<A>,
        lease: ExecutionLease,
        stage: String,
        successDetails: ((T) -> String)?,
        request: suspend (BorrowedAccount<A>) -> T,
    ): T {
        if (_stateFlow.value is BorrowedSessionState.Lost) {
            throw BorrowedAccountUnavailableException(
                IllegalStateException("borrowed session already lost"),
            )
        }
        var activeAccount = initial
        var refreshedForThisPage = false

        suspend fun execute(current: BorrowedAccount<A>): T = retryExecutor.execute(stage) {
            request(current)
        }

        suspend fun renewAndExecute(reason: RenewalReason): T {
            val renewed = lease.guarded { renew(activeAccount, reason) }
            val current = (renewed as? AcquireResult.Success)?.account
            if (current == null) {
                _stateFlow.value = BorrowedSessionState.Lost
                val cause = when (renewed) {
                    is AcquireResult.InvalidResponse -> renewed.cause
                    is AcquireResult.NotPremium ->
                        IllegalStateException("borrowed account is no longer premium")
                    else -> null
                } ?: IllegalStateException("borrowed account renewal failed (${renewed.reasonLabel})")
                throw BorrowedAccountUnavailableException(cause)
            }
            refreshedForThisPage = true
            activeAccount = current
            _stateFlow.update { state ->
                if (state is BorrowedSessionState.Lost) state else BorrowedSessionState.Active(current)
            }
            return execute(current)
        }

        try {
            val response = try {
                if (initial.isLocallyExpired(config.clock.wallTimeMillis())) {
                    observer.emit(
                        SessionEvent.LocalExpiryRefreshTriggered(
                            stage = stage,
                            accountUid = initial.uid,
                        ),
                    )
                    renewAndExecute(RenewalReason.LOCAL_EXPIRY)
                } else {
                    execute(initial)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (fatal: Error) {
                throw fatal
            } catch (error: Throwable) {
                if (refreshedForThisPage || !config.authFailureClassifier.isAccessTokenExpired(error)) {
                    throw error
                }
                observer.emit(
                    SessionEvent.AuthRejectedRetryTriggered(
                        stage = stage,
                        accountUid = activeAccount.uid,
                    ),
                )
                renewAndExecute(RenewalReason.AUTH_REJECTED)
            }
            val details = successDetails?.invoke(response)
            observer.emit(
                SessionEvent.RequestSucceeded(
                    stage = stage,
                    accountUid = activeAccount.uid,
                    details = details,
                ),
            )
            return response
        } catch (ce: CancellationException) {
            throw ce
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Throwable) {
            observer.emit(
                SessionEvent.RequestFailed(
                    stage = stage,
                    accountUid = activeAccount.uid,
                    cause = error,
                ),
            )
            throw error
        }
    }

    override suspend fun <T : Any> executeFirstPage(
        request: FirstPageRequest<A, T>,
    ): T {
        val preflightFailure = preflightDenyFailure()
        if (preflightFailure != null) {
            val reason = FallbackReason.AcquireFailed(preflightFailure)
            return request.hooks.aroundFlow {
                emitPreflightOutcome(request.requesterUid, preflightFailure)
                request.hooks.onFallback(reason)
                request.hooks.aroundFallbackRequest(reason) {
                    request.fallback(reason)
                }
            }
        }

        val firstRequestId = if (request.idempotencyEnabled) {
            config.requestIdGenerator.nextId()
        } else {
            null
        }

        val cached = safeCacheLookup(request.cache, firstRequestId, request.requestStage)
        if (cached != null) {
            markCursorFromCache(firstRequestId, request.idempotencyEnabled)
            request.hooks.onCacheHit(firstRequestId)
            return request.hooks.aroundFlow {
                request.hooks.aroundCacheHit(firstRequestId) {
                    request.postProcess(cached)
                }
            }
        }

        return request.hooks.aroundFlow {
            // Release the process-wide serial permit before executing own-account fallback so
            // slow fallback/retry traffic cannot head-of-line block other borrowed callers.
            val outcome = serialCoordinator.run(request.serialStage) { lease ->
                val result = lease.guarded { acquireReady(request.requesterUid, firstRequestId) }
                val active = (result as? AcquireResult.Success)?.account?.takeIf { !it.expired }
                if (active == null) {
                    val reason: FallbackReason = when (result) {
                        is AcquireResult.Failure -> FallbackReason.AcquireFailed(result)
                        is AcquireResult.Success -> FallbackReason.AccountExpired
                    }
                    return@run FirstPageOutcome.Fallback(reason)
                }
                request.hooks.onAccountBorrowed(active.uid)
                observer.emit(SessionEvent.BorrowedRequestStarted(request.requestStage, active.uid))
                try {
                    val page = request.hooks.aroundBorrowedRequest(active.uid, firstRequestId) {
                        val raw = executeWithRefresh(
                            initial = active,
                            lease = lease,
                            stage = request.requestStage,
                            successDetails = request.successDetails,
                            request = request.request,
                        )
                        safeCacheStore(request.cache, raw, request.requestStage)
                        request.postProcess(raw)
                    }
                    FirstPageOutcome.Completed(page)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (fatal: Error) {
                    throw fatal
                } catch (error: Throwable) {
                    val unavailable = findBorrowedAccountUnavailable(error) ?: throw error
                    FirstPageOutcome.Fallback(FallbackReason.AccountUnavailable(unavailable))
                }
            }
            when (outcome) {
                is FirstPageOutcome.Completed -> outcome.page
                is FirstPageOutcome.Fallback -> {
                    request.hooks.onFallback(outcome.reason)
                    request.hooks.aroundFallbackRequest(outcome.reason) {
                        request.fallback(outcome.reason)
                    }
                }
            }
        }
    }

    override suspend fun <T : Any> executeNextPage(
        request: NextPageRequest<A, T>,
    ): T {
        val initialState = _stateFlow.value
        when (initialState) {
            is BorrowedSessionState.Lost -> {
                observer.emit(
                    SessionEvent.PaginationEnded(
                        request.requestStage,
                        PaginationEndReason.ALREADY_LOST,
                    ),
                )
                return request.endPagination(PaginationEndReason.ALREADY_LOST)
            }
            is BorrowedSessionState.Idle -> {
                return request.hooks.aroundDirectRequest { request.directRequest() }
            }
            is BorrowedSessionState.Active,
            is BorrowedSessionState.CachedCursor,
            -> Unit
        }

        val allowCacheLookup = when (initialState) {
            is BorrowedSessionState.CachedCursor ->
                !isEngineClosed() && config.accessPolicy.evaluate() is AccessDecision.Allowed
            else -> true
        }

        val nextRequestId = if (request.idempotencyEnabled) {
            config.requestIdGenerator.nextId()
        } else {
            null
        }

        val cached = if (allowCacheLookup) {
            safeCacheLookup(request.cache, nextRequestId, request.requestStage)
        } else {
            null
        }
        if (cached != null) {
            return request.hooks.aroundCacheHit(nextRequestId) {
                request.postProcess(cached)
            }
        }

        val outcome = serialCoordinator.run(request.serialStage) { lease ->
            try {
                val active: BorrowedAccount<A> = when (val liveState = _stateFlow.value) {
                    is BorrowedSessionState.Lost -> {
                        observer.emit(
                            SessionEvent.PaginationEnded(
                                request.requestStage,
                                PaginationEndReason.ALREADY_LOST,
                            ),
                        )
                        return@run NextPageOutcome.Completed(
                            request.endPagination(PaginationEndReason.ALREADY_LOST),
                        )
                    }
                    is BorrowedSessionState.Idle -> {
                        return@run NextPageOutcome.Direct
                    }
                    is BorrowedSessionState.Active -> liveState.account
                    is BorrowedSessionState.CachedCursor -> {
                        observer.emit(
                            SessionEvent.CachedCursorBorrowStarted(
                                request.requestStage,
                                request.requesterUid,
                            ),
                        )
                        val handoff = lease.guarded { acquireForCachedCursor(request.requesterUid) }
                        val acquired = (handoff as? AcquireResult.Success)
                            ?.account
                            ?.takeIf { !it.expired }
                        if (acquired == null) {
                            _stateFlow.value = BorrowedSessionState.Lost
                            throw BorrowedAccountUnavailableException(
                                IllegalStateException("no borrowed account for cached cursor"),
                            )
                        }
                        acquired
                    }
                }

                observer.emit(SessionEvent.BorrowedRequestStarted(request.requestStage, active.uid))
                val page = request.hooks.aroundBorrowedRequest(active.uid, nextRequestId) {
                    val raw = executeWithRefresh(
                        initial = active,
                        lease = lease,
                        stage = request.requestStage,
                        successDetails = request.successDetails,
                        request = request.request,
                    )
                    safeCacheStore(request.cache, raw, request.requestStage)
                    request.postProcess(raw)
                }
                NextPageOutcome.Completed(page)
            } catch (ce: CancellationException) {
                throw ce
            } catch (fatal: Error) {
                throw fatal
            } catch (error: Throwable) {
                if (!error.isBorrowedAccountUnavailable()) throw error
                observer.emit(
                    SessionEvent.PaginationEnded(
                        request.requestStage,
                        PaginationEndReason.RENEWAL_FAILED,
                    ),
                )
                NextPageOutcome.Completed(request.endPagination(PaginationEndReason.RENEWAL_FAILED))
            }
        }
        return when (outcome) {
            is NextPageOutcome.Completed -> outcome.page
            NextPageOutcome.Direct -> request.hooks.aroundDirectRequest { request.directRequest() }
        }
    }

    private fun preflightDenyFailure(): AcquireResult.Failure<A>? = when {
        isEngineClosed() -> AcquireResult.Closed
        _stateFlow.value is BorrowedSessionState.Lost -> AcquireResult.InvalidResponse(
            BorrowedAccountUnavailableException(
                IllegalStateException("borrowed session already lost"),
            ),
        )
        else -> when (val decision = config.accessPolicy.evaluate()) {
            AccessDecision.Allowed -> null
            is AccessDecision.Denied -> AcquireResult.Disabled(decision.reason)
        }
    }

    private fun emitPreflightOutcome(requesterUid: Long, failure: AcquireResult.Failure<A>) {
        when (failure) {
            AcquireResult.Closed -> observer.emit(
                SessionEvent.FetchCompleted(
                    requesterUid = requesterUid,
                    outcome = FetchEventOutcome.Closed,
                ),
            )
            is AcquireResult.Disabled -> observer.emit(
                SessionEvent.FetchCompleted(
                    requesterUid = requesterUid,
                    outcome = FetchEventOutcome.Disabled(failure.reason),
                ),
            )
            else -> Unit
        }
    }

    private suspend fun <T : Any> safeCacheLookup(
        cache: BorrowedPageCache<T>?,
        requestId: String?,
        stage: String,
    ): T? {
        if (cache == null) return null
        return try {
            cache.lookup(requestId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            observer.emit(SessionEvent.CacheOperationFailed(stage, CacheOperation.LOOKUP, error))
            null
        }
    }

    private fun <T : Any> safeCacheStore(
        cache: BorrowedPageCache<T>?,
        page: T,
        stage: String,
    ) {
        if (cache == null) return
        try {
            cache.store(page)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            observer.emit(SessionEvent.CacheOperationFailed(stage, CacheOperation.STORE, error))
        }
    }

    private fun applyAcquireState(ready: AcquireResult<A>) {
        if (ready is AcquireResult.Success) {
            // Lost is strictly terminal and can never resurrect.
            _stateFlow.update { current ->
                if (current is BorrowedSessionState.Lost) current
                else BorrowedSessionState.Active(ready.account)
            }
            return
        }
        // Only degrade to Lost if this session previously held an active borrowed account.
        // Idle stays Idle (first-page fallback uses caller's own cursor); CachedCursor stays
        // CachedCursor until pagination terminates it.
        _stateFlow.update { current ->
            if (current is BorrowedSessionState.Active) BorrowedSessionState.Lost else current
        }
    }

    private suspend fun renew(stale: BorrowedAccount<A>, reason: RenewalReason): AcquireResult<A> {
        val nowBeforeRefresh = config.clock.wallTimeMillis()
        observer.emit(
            SessionEvent.RenewalStarted(
                accountUid = stale.uid,
                reason = reason,
                expiredAtMs = stale.expiresAtMs,
                overdueMs = (nowBeforeRefresh - stale.expiresAtMs).coerceAtLeast(0L),
            ),
        )
        val refreshToken = stale.refreshToken?.takeIf { it.isNotBlank() }
        if (refreshToken == null) {
            observer.emit(
                SessionEvent.RenewalValidationFailed(
                    accountUid = stale.uid,
                    reason = ValidationFailureReason.MISSING_REFRESH_TOKEN,
                ),
            )
            return AcquireResult.InvalidResponse(
                IllegalStateException("borrowed account refresh_token missing"),
            )
        }
        val uid = stale.uid.takeIf { it > 0L }
        if (uid == null) {
            observer.emit(
                SessionEvent.RenewalValidationFailed(
                    accountUid = stale.uid,
                    reason = ValidationFailureReason.INVALID_ACCOUNT_UID,
                ),
            )
            return AcquireResult.InvalidResponse(
                IllegalStateException("borrowed account uid missing"),
            )
        }

        observer.emit(SessionEvent.TokenRefreshStarted(accountUid = uid, reason = reason))
        val refreshOutcome = try {
            config.tokenRefresher.refresh(uid, refreshToken)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            TokenRefreshOutcome.Failed(error)
        }

        val tokens = when (refreshOutcome) {
            is TokenRefreshOutcome.Success -> {
                observer.emit(
                    SessionEvent.TokenRefreshSucceeded(
                        accountUid = uid,
                        reason = reason,
                        expiresInSeconds = refreshOutcome.tokens.expiresInSeconds,
                    ),
                )
                refreshOutcome.tokens
            }
            is TokenRefreshOutcome.InvalidRefreshToken -> {
                val persisted = safePersistInvalidRefreshToken(uid, refreshToken)
                observer.emit(
                    SessionEvent.TokenRefreshInvalidGrant(
                        accountUid = uid,
                        reason = reason,
                        invalidationQueued = persisted,
                        cause = refreshOutcome.cause,
                    ),
                )
                return AcquireResult.InvalidResponse(refreshOutcome.cause)
            }
            is TokenRefreshOutcome.Failed -> {
                observer.emit(
                    SessionEvent.TokenRefreshFailed(
                        accountUid = uid,
                        reason = reason,
                        cause = refreshOutcome.cause,
                    ),
                )
                return AcquireResult.InvalidResponse(refreshOutcome.cause)
            }
        }

        val attributedPremium = MembershipPolicy.attribute(
            reportedUid = tokens.reportedUid,
            expectedUid = uid,
            reportedIsPremium = tokens.reportedIsPremium,
        )
        val updatedPayload = config.payloadUpdater.applyRefresh(
            previous = stale.payload,
            tokens = tokens,
            attributedPremium = attributedPremium,
        )
        val updatedAtMs = config.clock.wallTimeMillis()
        val expiresAtMs = updatedAtMs + config.tokenValidDurationMs
        val refreshedAccount = BorrowedAccount.create(updatedPayload) {
            this.uid = uid
            this.accessToken = tokens.accessToken
            this.refreshToken = tokens.refreshToken
            this.updatedAtMs = updatedAtMs
            this.expiresAtMs = expiresAtMs
            this.expired = false
            this.isPremium = MembershipPolicy.merge(stale.isPremium, attributedPremium)
        }

        // Persist before any cancellable network delivery. Once OAuth rotates a refresh token,
        // returning Success is only safe if the durable outbox owns the new token.
        val (persisted, persistError) = safePersistOnline(uid, updatedPayload)
        if (!persisted) {
            observer.emit(SessionEvent.OnlineReportPersistFailed(accountUid = uid, reason = reason))
            return AcquireResult.InvalidResponse(
                persistError ?: IllegalStateException("refreshed account payload could not be persisted"),
            )
        }

        observer.emit(SessionEvent.OnlineReportStarted(accountUid = uid, reason = reason))
        val reportResult = safeAttemptOnline(uid)
        observer.emit(
            SessionEvent.OnlineReportCompleted(
                accountUid = uid,
                reason = reason,
                outcome = reportResult,
            ),
        )

        if (attributedPremium == false) {
            observer.emit(
                SessionEvent.NotPremiumDetected(
                    accountUid = uid,
                    renewalReason = reason,
                    reportOutcome = reportResult.name.lowercase(),
                ),
            )
            return AcquireResult.NotPremium(uid = uid, payload = updatedPayload)
        }

        observer.emit(
            SessionEvent.RenewalSucceeded(
                accountUid = uid,
                updatedAtMs = updatedAtMs,
                expiresAtMs = expiresAtMs,
            ),
        )
        return AcquireResult.Success(refreshedAccount)
    }

    private suspend fun reportNotPremium(uid: Long, payload: A) {
        val (persisted, _) = safePersistOnline(uid, payload)
        val reportOutcome = if (persisted) {
            safeAttemptOnline(uid).name.lowercase()
        } else {
            "persist_failed"
        }
        observer.emit(
            SessionEvent.NotPremiumDetected(
                accountUid = uid,
                renewalReason = null,
                reportOutcome = reportOutcome,
            ),
        )
    }

    private suspend fun safePersistOnline(uid: Long, payload: A): Pair<Boolean, Exception?> =
        withContext(NonCancellable) {
            try {
                config.reportStore.persistOnline(uid, payload) to null
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                false to error
            }
        }

    private suspend fun safePersistInvalidRefreshToken(uid: Long, refreshToken: String): Boolean =
        withContext(NonCancellable) {
            try {
                config.reportStore.persistInvalidRefreshToken(uid, refreshToken)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                false
            }
        }

    private suspend fun safeAttemptOnline(uid: Long): ReportDeliveryOutcome =
        try {
            config.reportStore.attemptOnline(uid)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            ReportDeliveryOutcome.FAILED
        }

    private sealed interface FirstPageOutcome<out T : Any> {
        class Completed<out T : Any>(val page: T) : FirstPageOutcome<T>
        class Fallback(val reason: FallbackReason) : FirstPageOutcome<Nothing>
    }

    private sealed interface NextPageOutcome<out T : Any> {
        class Completed<out T : Any>(val page: T) : NextPageOutcome<T>
        data object Direct : NextPageOutcome<Nothing>
    }

    private fun toFetchOutcome(result: AcquireResult<A>): FetchEventOutcome = when (result) {
        is AcquireResult.Success -> {
            val now = config.clock.wallTimeMillis()
            FetchEventOutcome.Success(
                accountUid = result.account.uid,
                expired = result.account.expired,
                updatedAtMs = result.account.updatedAtMs,
                expiresAtMs = result.account.expiresAtMs,
                ageMs = (now - result.account.updatedAtMs).coerceAtLeast(0L),
                remainingMs = (result.account.expiresAtMs - now).coerceAtLeast(0L),
            )
        }
        is AcquireResult.Disabled -> FetchEventOutcome.Disabled(result.reason)
        AcquireResult.Closed -> FetchEventOutcome.Closed
        AcquireResult.NoAccount -> FetchEventOutcome.NoAccount
        is AcquireResult.NotPremium -> FetchEventOutcome.NotPremium(result.uid)
        is AcquireResult.RateLimited -> FetchEventOutcome.RateLimited(result.info)
        is AcquireResult.HttpFailure -> FetchEventOutcome.HttpFailure(result.statusCode)
        AcquireResult.InvalidRequest -> FetchEventOutcome.InvalidRequest
        is AcquireResult.NetworkFailure -> FetchEventOutcome.NetworkFailure(result.cause)
        is AcquireResult.InvalidResponse -> FetchEventOutcome.InvalidResponse(result.cause)
    }

    private fun findBorrowedAccountUnavailable(error: Throwable): BorrowedAccountUnavailableException? =
        generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .filterIsInstance<BorrowedAccountUnavailableException>()
            .firstOrNull()

    private companion object {
        const val STAGE_DISPATCH = "dispatch"
    }
}
