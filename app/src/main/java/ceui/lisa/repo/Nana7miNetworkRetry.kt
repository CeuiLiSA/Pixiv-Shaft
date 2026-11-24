package ceui.lisa.repo

import ceui.pixiv.nana7mi.host.TimberSessionObserver
import ceui.pixiv.nana7mi.session.NetworkRetryExecutor
import ceui.pixiv.nana7mi.session.NetworkRetryPolicy

/**
 * Thin host wrapper over `:nana7mi`'s [NetworkRetryExecutor] for non-session search endpoints
 * (popular preview, plain search, search cache lookup) so they share the exact same bounded-budget
 * retry semantics and structured logging.
 */
private val sharedRetryExecutor: NetworkRetryExecutor = NetworkRetryExecutor(
    defaultPolicy = NetworkRetryPolicy.DEFAULT,
    observer = TimberSessionObserver,
)

internal suspend fun <T> retryNana7miNetworkCall(
    stage: String,
    totalBudgetMs: Long = NetworkRetryPolicy.DEFAULT_BUDGET_MS,
    retryDelayMs: Long = NetworkRetryPolicy.DEFAULT_RETRY_DELAY_MS,
    source: suspend () -> T,
): T = sharedRetryExecutor.execute(
    stage = stage,
    policy = NetworkRetryPolicy.create {
        this.totalBudgetMs = totalBudgetMs
        this.retryDelayMs = retryDelayMs
    },
    source = source,
)

internal suspend fun <T> retryNana7miNetworkResult(
    stage: String,
    totalBudgetMs: Long = NetworkRetryPolicy.DEFAULT_BUDGET_MS,
    retryDelayMs: Long = NetworkRetryPolicy.DEFAULT_RETRY_DELAY_MS,
    failureOf: (T) -> Throwable?,
    source: suspend () -> T,
): T = sharedRetryExecutor.executeResult(
    stage = stage,
    policy = NetworkRetryPolicy.create {
        this.totalBudgetMs = totalBudgetMs
        this.retryDelayMs = retryDelayMs
    },
    failureOf = failureOf,
    source = source,
)
