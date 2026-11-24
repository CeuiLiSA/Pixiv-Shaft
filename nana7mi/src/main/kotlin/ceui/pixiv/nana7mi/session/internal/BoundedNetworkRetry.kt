package ceui.pixiv.nana7mi.session.internal

import ceui.pixiv.nana7mi.session.NetworkRetryExecutor
import ceui.pixiv.nana7mi.session.NetworkRetryPolicy
import ceui.pixiv.nana7mi.session.SessionClock
import ceui.pixiv.nana7mi.session.SessionEvent
import ceui.pixiv.nana7mi.session.TransportFailureClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal class BoundedNetworkRetry(
    override val defaultPolicy: NetworkRetryPolicy,
    private val classifier: TransportFailureClassifier,
    private val clock: SessionClock,
    private val observer: SafeSessionObserver,
) : NetworkRetryExecutor {

    private class AttemptBox<out T>(val value: T)

    override suspend fun <T> execute(
        stage: String,
        policy: NetworkRetryPolicy,
        source: suspend () -> T,
    ): T {
        val startedAtNanos = clock.monotonicNanos()
        val firstError: Throwable = try {
            return source()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Throwable) {
            error
        }

        var lastError = firstError
        for (retryIndex in 1..policy.maxRetries) {
            val failure = classifier.classify(lastError)
            if (failure?.retryable != true) throw lastError
            val remainingMs = remainingBudgetMs(startedAtNanos, policy)
            if (remainingMs <= 0L || remainingMs < policy.minRetryWindowMs) throw lastError

            observer.emit(
                SessionEvent.RetryScheduled(
                    stage = stage,
                    failureKind = failure,
                    attempt = retryIndex + 1,
                    remainingBudgetMs = remainingMs,
                ),
            )
            if (policy.retryDelayMs > 0L) delay(policy.retryDelayMs)
            val boxed = try {
                // withTimeoutOrNull matches only its own TimeoutCoroutine identity, so both
                // outer cancellation/timeouts and nested withTimeout inside source() propagate.
                withTimeoutOrNull(remainingMs) { AttemptBox(source()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (error: Throwable) {
                lastError = error
                continue
            }
            // Internal retry budget expired, not caller cancellation. Preserve the original
            // transport failure so fallback and telemetry report the true root cause.
            if (boxed == null) throw firstError
            return boxed.value
        }
        throw lastError
    }

    override suspend fun <T> executeResult(
        stage: String,
        policy: NetworkRetryPolicy,
        failureOf: (T) -> Throwable?,
        source: suspend () -> T,
    ): T {
        val startedAtNanos = clock.monotonicNanos()
        val firstResult = source()
        var currentResult = firstResult

        for (retryIndex in 1..policy.maxRetries) {
            val error = failureOf(currentResult) ?: return currentResult
            val failure = classifier.classify(error)
            if (failure?.retryable != true) return currentResult
            val remainingMs = remainingBudgetMs(startedAtNanos, policy)
            if (remainingMs <= 0L || remainingMs < policy.minRetryWindowMs) return currentResult

            observer.emit(
                SessionEvent.RetryScheduled(
                    stage = stage,
                    failureKind = failure,
                    attempt = retryIndex + 1,
                    remainingBudgetMs = remainingMs,
                ),
            )
            if (policy.retryDelayMs > 0L) delay(policy.retryDelayMs)
            val boxed = withTimeoutOrNull(remainingMs) { AttemptBox(source()) }
                ?: return firstResult
            currentResult = boxed.value
        }
        return currentResult
    }

    private fun remainingBudgetMs(startedAtNanos: Long, policy: NetworkRetryPolicy): Long {
        val elapsedMs = ((clock.monotonicNanos() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        return (policy.totalBudgetMs - elapsedMs - policy.retryDelayMs).coerceAtLeast(0L)
    }
}
