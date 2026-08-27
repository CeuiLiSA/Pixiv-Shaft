package ceui.lisa.repo

import ceui.lisa.http.classifyTransportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * One retry for idempotent Nana7mi network operations. The retry window is measured from the
 * first attempt's start, so a slow first failure cannot open a fresh full-length second timeout.
 *
 * The caller must reuse the same request id for any operation that can consume quota. A full first
 * attempt leaves no useful budget and therefore is not retried; fast DNS/connect/socket failures do.
 */
internal suspend fun <T> retryNana7miNetworkCall(
    stage: String,
    totalBudgetMs: Long = NANA7MI_RETRY_BUDGET_MS,
    retryDelayMs: Long = NANA7MI_RETRY_DELAY_MS,
    source: suspend () -> T,
): T {
    val startedAt = System.nanoTime()
    val firstError = try {
        return source()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        error
    }
    val failure = classifyTransportFailure(firstError)
    if (failure?.retryable != true) throw firstError
    val remainingMs = retryBudgetRemaining(startedAt, totalBudgetMs, retryDelayMs)
    if (remainingMs < NANA7MI_MIN_RETRY_WINDOW_MS) throw firstError

    Timber.tag(NANA7MI_RETRY_LOG_TAG).w(
        "stage=%s result=%s action=retry attempt=2 remaining_ms=%d",
        stage,
        failure.wire,
        remainingMs,
    )
    if (retryDelayMs > 0L) delay(retryDelayMs)
    return try {
        withTimeout(remainingMs) { source() }
    } catch (timeout: TimeoutCancellationException) {
        if (!currentCoroutineContext().isActive) throw timeout
        // The retry budget is an implementation deadline, not user cancellation. Preserve the
        // original transport failure so fallback and telemetry keep the correct outcome.
        throw firstError
    }
}

/** Result-returning twin used by PixshaftApi.fetchNana7mi, which converts IOException to a value. */
internal suspend fun <T> retryNana7miNetworkResult(
    stage: String,
    totalBudgetMs: Long = NANA7MI_RETRY_BUDGET_MS,
    retryDelayMs: Long = NANA7MI_RETRY_DELAY_MS,
    failureOf: (T) -> Throwable?,
    source: suspend () -> T,
): T {
    val startedAt = System.nanoTime()
    val first = source()
    val error = failureOf(first) ?: return first
    val failure = classifyTransportFailure(error)
    if (failure?.retryable != true) return first
    val remainingMs = retryBudgetRemaining(startedAt, totalBudgetMs, retryDelayMs)
    if (remainingMs < NANA7MI_MIN_RETRY_WINDOW_MS) return first

    Timber.tag(NANA7MI_RETRY_LOG_TAG).w(
        "stage=%s result=%s action=retry attempt=2 remaining_ms=%d",
        stage,
        failure.wire,
        remainingMs,
    )
    if (retryDelayMs > 0L) delay(retryDelayMs)
    return try {
        withTimeout(remainingMs) { source() }
    } catch (timeout: TimeoutCancellationException) {
        if (!currentCoroutineContext().isActive) throw timeout
        first
    }
}

private fun retryBudgetRemaining(startedAt: Long, totalMs: Long, delayMs: Long): Long {
    val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
    return (totalMs - elapsedMs - delayMs).coerceAtLeast(0L)
}

private const val NANA7MI_RETRY_BUDGET_MS = 15_000L
private const val NANA7MI_RETRY_DELAY_MS = 250L
private const val NANA7MI_MIN_RETRY_WINDOW_MS = 1_000L
private const val NANA7MI_RETRY_LOG_TAG = "Nana7miRetry"
