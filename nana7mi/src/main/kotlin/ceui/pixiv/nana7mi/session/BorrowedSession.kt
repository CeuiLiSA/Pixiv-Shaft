package ceui.pixiv.nana7mi.session

import kotlinx.coroutines.flow.StateFlow

/**
 * Per-flow around-advice / telemetry hooks for [BorrowedSession.executeFirstPage].
 *
 * Every method defaults to a pass-through so callers only implement the points they observe.
 */
public interface FirstPageHooks {
    /** Wraps the complete first-page flow (either cache hit or live borrowed/fallback path). */
    public suspend fun <T : Any> aroundFlow(block: suspend () -> T): T = block()

    /** Invoked right before [aroundFlow] when the first-page cache hits. */
    public fun onCacheHit(requestId: String?) {}

    /** Wraps post-processing of a cache-hit first page inside [aroundFlow]. */
    public suspend fun <T : Any> aroundCacheHit(
        requestId: String?,
        block: suspend () -> T,
    ): T = block()

    /** Invoked inside the serial lease once [BorrowedSession.acquireReady] yields a usable account. */
    public fun onAccountBorrowed(accountUid: Long) {}

    /** Wraps the live borrowed-account request + cache store + post-processing. */
    public suspend fun <T : Any> aroundBorrowedRequest(
        accountUid: Long,
        requestId: String?,
        block: suspend () -> T,
    ): T = block()

    /** Invoked when acquisition or renewal fails and the flow transitions to the fallback path. */
    public fun onFallback(reason: FallbackReason) {}

    /** Wraps the fallback request after [onFallback]. */
    public suspend fun <T : Any> aroundFallbackRequest(
        reason: FallbackReason,
        block: suspend () -> T,
    ): T = block()

    public companion object {
        @JvmField
        public val NONE: FirstPageHooks = object : FirstPageHooks {}
    }
}

/**
 * Per-flow around-advice / telemetry hooks for [BorrowedSession.executeNextPage].
 */
public interface NextPageHooks {
    /** Wraps direct (non-borrowed) pagination when the session is [BorrowedSessionState.Idle]. */
    public suspend fun <T : Any> aroundDirectRequest(block: suspend () -> T): T = block()

    /** Wraps post-processing of a cache-hit pagination page. */
    public suspend fun <T : Any> aroundCacheHit(
        requestId: String?,
        block: suspend () -> T,
    ): T = block()

    /** Wraps a live borrowed-account pagination request + cache store + post-processing. */
    public suspend fun <T : Any> aroundBorrowedRequest(
        accountUid: Long,
        requestId: String?,
        block: suspend () -> T,
    ): T = block()

    public companion object {
        @JvmField
        public val NONE: NextPageHooks = object : NextPageHooks {}
    }
}

/**
 * Immutable execution specification for [BorrowedSession.executeFirstPage].
 *
 * Using an explicit parameter object with [Builder] keeps [BorrowedSession] binary-compatible as
 * future execution options are introduced and avoids telescoping multi-parameter method signatures.
 */
public class FirstPageRequest<A : Any, T : Any> private constructor(
    public val requesterUid: Long,
    public val idempotencyEnabled: Boolean,
    public val serialStage: String,
    public val requestStage: String,
    public val cache: BorrowedPageCache<T>?,
    public val hooks: FirstPageHooks,
    public val successDetails: ((T) -> String)?,
    public val postProcess: suspend (T) -> T,
    public val fallback: suspend (FallbackReason) -> T,
    public val request: suspend (BorrowedAccount<A>) -> T,
) {
    @Nana7miDsl
    public class Builder<A : Any, T : Any> {
        public var requesterUid: Long = 0L
        public var idempotencyEnabled: Boolean = true
        public var serialStage: String = DEFAULT_SERIAL_STAGE
        public var requestStage: String = DEFAULT_REQUEST_STAGE
        public var cache: BorrowedPageCache<T>? = null
        public var hooks: FirstPageHooks = FirstPageHooks.NONE

        private var successDetailsProvider: ((T) -> String)? = null
        private var postProcessor: suspend (T) -> T = { it }
        private var fallbackHandler: (suspend (FallbackReason) -> T)? = null
        private var requestExecutor: (suspend (BorrowedAccount<A>) -> T)? = null

        public fun requesterUid(uid: Long): Builder<A, T> = apply { this.requesterUid = uid }
        public fun idempotencyEnabled(enabled: Boolean): Builder<A, T> = apply { this.idempotencyEnabled = enabled }
        public fun serialStage(stage: String): Builder<A, T> = apply { this.serialStage = stage }
        public fun requestStage(stage: String): Builder<A, T> = apply { this.requestStage = stage }
        public fun cache(cache: BorrowedPageCache<T>?): Builder<A, T> = apply { this.cache = cache }
        public fun hooks(hooks: FirstPageHooks): Builder<A, T> = apply { this.hooks = hooks }

        public fun describeSuccess(provider: (T) -> String): Builder<A, T> = apply {
            this.successDetailsProvider = provider
        }

        public fun postProcess(transform: suspend (T) -> T): Builder<A, T> = apply {
            this.postProcessor = transform
        }

        public fun fallback(handler: suspend (FallbackReason) -> T): Builder<A, T> = apply {
            this.fallbackHandler = handler
        }

        public fun request(executor: suspend (BorrowedAccount<A>) -> T): Builder<A, T> = apply {
            this.requestExecutor = executor
        }

        public fun build(): FirstPageRequest<A, T> {
            require(serialStage.isNotBlank()) { "serialStage must not be blank" }
            require(requestStage.isNotBlank()) { "requestStage must not be blank" }
            return FirstPageRequest(
                requesterUid = requesterUid,
                idempotencyEnabled = idempotencyEnabled,
                serialStage = serialStage,
                requestStage = requestStage,
                cache = cache,
                hooks = hooks,
                successDetails = successDetailsProvider,
                postProcess = postProcessor,
                fallback = requireNotNull(fallbackHandler) { "fallback handler must be configured" },
                request = requireNotNull(requestExecutor) { "request executor must be configured" },
            )
        }
    }

    public companion object {
        public const val DEFAULT_SERIAL_STAGE: String = "first_page"
        public const val DEFAULT_REQUEST_STAGE: String = "borrowed_first_page"

        @JvmStatic
        public fun <A : Any, T : Any> builder(): Builder<A, T> = Builder()

        public inline fun <A : Any, T : Any> create(
            block: Builder<A, T>.() -> Unit,
        ): FirstPageRequest<A, T> = Builder<A, T>().apply(block).build()
    }
}

/**
 * Immutable execution specification for [BorrowedSession.executeNextPage].
 */
public class NextPageRequest<A : Any, T : Any> private constructor(
    public val requesterUid: Long,
    public val idempotencyEnabled: Boolean,
    public val serialStage: String,
    public val requestStage: String,
    public val cache: BorrowedPageCache<T>?,
    public val hooks: NextPageHooks,
    public val successDetails: ((T) -> String)?,
    public val postProcess: suspend (T) -> T,
    public val directRequest: suspend () -> T,
    public val endPagination: (PaginationEndReason) -> T,
    public val request: suspend (BorrowedAccount<A>) -> T,
) {
    @Nana7miDsl
    public class Builder<A : Any, T : Any> {
        public var requesterUid: Long = 0L
        public var idempotencyEnabled: Boolean = true
        public var serialStage: String = DEFAULT_SERIAL_STAGE
        public var requestStage: String = DEFAULT_REQUEST_STAGE
        public var cache: BorrowedPageCache<T>? = null
        public var hooks: NextPageHooks = NextPageHooks.NONE

        private var successDetailsProvider: ((T) -> String)? = null
        private var postProcessor: suspend (T) -> T = { it }
        private var directExecutor: (suspend () -> T)? = null
        private var endPaginationHandler: ((PaginationEndReason) -> T)? = null
        private var requestExecutor: (suspend (BorrowedAccount<A>) -> T)? = null

        public fun requesterUid(uid: Long): Builder<A, T> = apply { this.requesterUid = uid }
        public fun idempotencyEnabled(enabled: Boolean): Builder<A, T> = apply { this.idempotencyEnabled = enabled }
        public fun serialStage(stage: String): Builder<A, T> = apply { this.serialStage = stage }
        public fun requestStage(stage: String): Builder<A, T> = apply { this.requestStage = stage }
        public fun cache(cache: BorrowedPageCache<T>?): Builder<A, T> = apply { this.cache = cache }
        public fun hooks(hooks: NextPageHooks): Builder<A, T> = apply { this.hooks = hooks }

        public fun describeSuccess(provider: (T) -> String): Builder<A, T> = apply {
            this.successDetailsProvider = provider
        }

        public fun postProcess(transform: suspend (T) -> T): Builder<A, T> = apply {
            this.postProcessor = transform
        }

        public fun directRequest(executor: suspend () -> T): Builder<A, T> = apply {
            this.directExecutor = executor
        }

        public fun endPagination(handler: (PaginationEndReason) -> T): Builder<A, T> = apply {
            this.endPaginationHandler = handler
        }

        public fun request(executor: suspend (BorrowedAccount<A>) -> T): Builder<A, T> = apply {
            this.requestExecutor = executor
        }

        public fun build(): NextPageRequest<A, T> {
            require(serialStage.isNotBlank()) { "serialStage must not be blank" }
            require(requestStage.isNotBlank()) { "requestStage must not be blank" }
            return NextPageRequest(
                requesterUid = requesterUid,
                idempotencyEnabled = idempotencyEnabled,
                serialStage = serialStage,
                requestStage = requestStage,
                cache = cache,
                hooks = hooks,
                successDetails = successDetailsProvider,
                postProcess = postProcessor,
                directRequest = requireNotNull(directExecutor) { "directRequest executor must be configured" },
                endPagination = requireNotNull(endPaginationHandler) { "endPagination handler must be configured" },
                request = requireNotNull(requestExecutor) { "request executor must be configured" },
            )
        }
    }

    public companion object {
        public const val DEFAULT_SERIAL_STAGE: String = "next_page"
        public const val DEFAULT_REQUEST_STAGE: String = "borrowed_next_page"

        @JvmStatic
        public fun <A : Any, T : Any> builder(): Builder<A, T> = Builder()

        public inline fun <A : Any, T : Any> create(
            block: Builder<A, T>.() -> Unit,
        ): NextPageRequest<A, T> = Builder<A, T>().apply(block).build()
    }
}

/**
 * Isolated borrowed-account session for a single search/cursor generation.
 *
 * Create a fresh instance via [Nana7miSessionEngine.newSession] whenever a new first page starts so
 * late completions from a previous search can never mutate or contaminate a newer session.
 *
 * Exposes both low-level lifecycle primitives ([markCursorFromCache], [acquireReady],
 * [acquireForCachedCursor], [executeWithRefresh]) and high-level orchestrated flows
 * ([executeFirstPage], [executeNextPage]) that coordinate caching, fair serialization, guarded
 * borrowing/renewal, fallback routing, and safe pagination termination.
 */
public interface BorrowedSession<A : Any> {

    /** Current atomic snapshot of the session's finite state machine. */
    public val state: BorrowedSessionState<A>

    /** Read-only reactive stream of state transitions for this session. */
    public val stateFlow: StateFlow<BorrowedSessionState<A>>

    /** Active borrowed account, or `null` when [state] is not [BorrowedSessionState.Active]. */
    public val currentAccount: BorrowedAccount<A>?
        get() = (state as? BorrowedSessionState.Active<A>)?.account

    /** `true` once a borrowed account or cached premium cursor permanently failed for this session. */
    public val isLost: Boolean
        get() = state is BorrowedSessionState.Lost

    /** `true` while this session is backed by a cached first-page cursor awaiting deferred borrowing. */
    public val isCursorFromCache: Boolean
        get() = state is BorrowedSessionState.CachedCursor

    /**
     * Transitions an [BorrowedSessionState.Idle] session to [BorrowedSessionState.CachedCursor],
     * recording the paid first-page [paidRequestId] for deferred handoff on pagination cache miss.
     *
     * @throws IllegalStateException if this session is not currently [BorrowedSessionState.Idle].
     */
    public fun markCursorFromCache(paidRequestId: String?, requestIdRequired: Boolean)

    /**
     * Dispatches one borrowed account for [requesterUid]. If the backend flags the account as
     * expired, refreshes and durably re-reports it before returning [AcquireResult.Success].
     *
     * Retries transient transport failures only when [requestId] is non-null (server supports
     * idempotency); legacy `null` calls are never replayed to avoid double-charging quota.
     */
    public suspend fun acquireReady(
        requesterUid: Long,
        requestId: String? = null,
    ): AcquireResult<A>

    /**
     * Borrows on demand for a [BorrowedSessionState.CachedCursor] session.
     *
     * Offers the paid first-page request id first so the backend can hand over an account without
     * charging a second search. If the replay window has expired (HTTP 409), retries once with a
     * fresh request id generated by [RequestIdGenerator].
     */
    public suspend fun acquireForCachedCursor(requesterUid: Long): AcquireResult<A>

    /**
     * Executes an idempotent upstream [request] with [initial], transparently refreshing once
     * inside [lease] when locally expired or when [AuthFailureClassifier.isAccessTokenExpired]
     * matches the upstream failure.
     *
     * On renewal failure (including loss of premium membership), transitions the session to
     * [BorrowedSessionState.Lost] and throws [BorrowedAccountUnavailableException].
     */
    public suspend fun <T : Any> executeWithRefresh(
        initial: BorrowedAccount<A>,
        lease: ExecutionLease,
        stage: String = "request",
        successDetails: ((T) -> String)? = null,
        request: suspend (BorrowedAccount<A>) -> T,
    ): T

    /**
     * Orchestrates a complete borrowed first-page flow as described by [request].
     */
    public suspend fun <T : Any> executeFirstPage(
        request: FirstPageRequest<A, T>,
    ): T

    /**
     * Orchestrates a complete pagination step respecting this session's state as described by [request].
     */
    public suspend fun <T : Any> executeNextPage(
        request: NextPageRequest<A, T>,
    ): T
}

/** Kotlin DSL convenience overload for [BorrowedSession.executeFirstPage]. */
public suspend inline fun <A : Any, T : Any> BorrowedSession<A>.executeFirstPage(
    block: FirstPageRequest.Builder<A, T>.() -> Unit,
): T = executeFirstPage(FirstPageRequest.Builder<A, T>().apply(block).build())

/** Kotlin DSL convenience overload for [BorrowedSession.executeNextPage]. */
public suspend inline fun <A : Any, T : Any> BorrowedSession<A>.executeNextPage(
    block: NextPageRequest.Builder<A, T>.() -> Unit,
): T = executeNextPage(NextPageRequest.Builder<A, T>().apply(block).build())
