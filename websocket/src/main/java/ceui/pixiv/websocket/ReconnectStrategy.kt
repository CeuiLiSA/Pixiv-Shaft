package ceui.pixiv.websocket

import kotlin.math.pow
import kotlin.random.Random

/**
 * Decides how long to wait before the next reconnect attempt — and whether
 * to give up entirely based on *why* the previous attempt failed.
 *
 * Implementations are pure functions of `(attempt, failure)` — they hold no
 * mutable state, so the same strategy can be safely reused across multiple
 * [RobustWebSocketClient] instances or across reconnect cycles within the
 * same instance (the client tracks the attempt counter itself).
 */
fun interface ReconnectStrategy {

    /**
     * @param attempt 1-based attempt number (1 = first retry after a failure)
     * @param failure structured reason the previous connection ended; lets
     *                the strategy short-circuit on fatal codes (e.g. 401, 1008)
     *                instead of retrying forever
     * @return delay in milliseconds before the next attempt, or `null` to give
     *         up (the client will transition to [WebSocketState.Disconnected]).
     */
    fun nextDelayMillis(attempt: Int, failure: FailureContext): Long?

    companion object {
        /** Never auto-reconnect — fail fast on the first error. */
        val NoRetry: ReconnectStrategy = ReconnectStrategy { _, _ -> null }
    }
}

/**
 * Exponential backoff with full jitter, capped at a max delay and (optionally)
 * a max number of attempts. Skips obviously-fatal failures by default — see
 * [shouldReconnect] and [DEFAULT_SHOULD_RECONNECT].
 *
 * The delay for attempt `n` is computed as:
 *
 * ```
 *   base   = initialDelayMillis * (multiplier ^ (n - 1))
 *   capped = min(base, maxDelayMillis)
 *   jitter = capped * jitterFactor * random(-1, +1)
 *   delay  = max(0, capped + jitter)
 * ```
 *
 * **Why jitter?** Without it, every client that lost a connection at roughly
 * the same time (regional outage, server restart) reconnects in lockstep,
 * producing a thundering-herd spike on the server. Symmetric jitter spreads
 * the herd across a window proportional to the current backoff.
 *
 * Defaults are tuned for chat / notification connections: 1s, 2s, 4s, 8s,
 * 16s, 30s, 30s, … with ±20% jitter, retrying forever — except for fatal
 * failures, which short-circuit immediately.
 *
 * @param initialDelayMillis base delay for the first attempt
 * @param maxDelayMillis     ceiling — no individual delay exceeds this
 * @param multiplier         exponent base; 2.0 = doubling
 * @param maxAttempts        give up after this many failed attempts
 *                           (default = unlimited)
 * @param jitterFactor       proportional jitter ±this fraction of the capped
 *                           delay; 0.0 = deterministic, 0.2 = ±20%
 * @param random             RNG (override for tests)
 * @param shouldReconnect    predicate that returns `false` for failures the
 *                           strategy should give up on immediately. Default
 *                           is [DEFAULT_SHOULD_RECONNECT], which rejects the
 *                           clearly-fatal close codes and HTTP statuses;
 *                           override to add app-specific 4xxx close codes
 *                           or relax the defaults.
 */
class ExponentialBackoffWithJitter(
    private val initialDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 30_000,
    private val multiplier: Double = 2.0,
    private val maxAttempts: Int = Int.MAX_VALUE,
    private val jitterFactor: Double = 0.2,
    private val random: Random = Random.Default,
    private val shouldReconnect: (FailureContext) -> Boolean = DEFAULT_SHOULD_RECONNECT,
) : ReconnectStrategy {

    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be > 0" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be in [0, 1]" }
    }

    override fun nextDelayMillis(attempt: Int, failure: FailureContext): Long? {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        if (!shouldReconnect(failure)) return null
        if (attempt > maxAttempts) return null
        // pow on a Double is fine here — the values stay well below
        // 2^53 even for absurd attempt counts because we cap immediately.
        val base = (initialDelayMillis.toDouble() * multiplier.pow((attempt - 1).toDouble()))
            .toLong()
            .coerceAtLeast(initialDelayMillis)
        val capped = base.coerceAtMost(maxDelayMillis)
        val jitter = (capped * jitterFactor * (random.nextDouble() * 2.0 - 1.0)).toLong()
        return (capped + jitter).coerceAtLeast(0L)
    }

    companion object {

        /**
         * Close codes that almost always indicate a permanent client/protocol
         * problem — retrying just produces the same failure on every attempt.
         *
         *  - **1002** Protocol error
         *  - **1003** Unsupported data
         *  - **1007** Invalid frame payload data
         *  - **1008** Policy violation (commonly used for "you were kicked")
         *  - **1009** Message too big
         *  - **1010** Mandatory extension missing
         *
         * Codes outside this set — including 1000/1001 (normal/going away),
         * 1011 (server error), 1012-1014 (restart / try again / bad gateway),
         * and the 4000-4999 application-defined range — are treated as
         * transient by default. Override [shouldReconnect] if your protocol
         * uses a 4xxx code as a "stop reconnecting" signal.
         */
        val FATAL_CLOSE_CODES: Set<Int> = setOf(1002, 1003, 1007, 1008, 1009, 1010)

        /**
         * HTTP upgrade response codes that almost always indicate a permanent
         * problem with the request itself — credentials, routing, or
         * authorization. Retrying with the *same* request will fail the same
         * way; the client must change something (refresh token, switch URL)
         * before another connect makes sense.
         *
         *  - **401** Unauthorized — token missing/expired
         *  - **403** Forbidden — token valid but lacks permission
         *  - **404** Not Found — wrong endpoint
         *  - **410** Gone — endpoint permanently retired
         *
         * 429 (Too Many Requests) and 5xx are deliberately *not* in this
         * set — they are transient and benefit from backoff retry.
         *
         * **Interaction with [WebSocketAuthProvider]:** when a provider is
         * wired up on [RobustWebSocketClient], auth failures (as determined
         * by [WebSocketAuthProvider.isAuthFailure] — 401 by default) are
         * intercepted by the client's dedicated auth-refresh path *before*
         * they reach this strategy. That means 401 in this set only takes
         * effect when no provider is configured, ensuring a bare client
         * doesn't waste retries on a permanently-rejected upgrade.
         */
        val FATAL_HTTP_CODES: Set<Int> = setOf(401, 403, 404, 410)

        /**
         * Default predicate used by [ExponentialBackoffWithJitter] to decide
         * whether a failure is worth retrying. Returns `false` for codes in
         * [FATAL_CLOSE_CODES] / [FATAL_HTTP_CODES], `true` for everything
         * else.
         *
         * Compose with this to add app-specific rules:
         *
         * ```
         * ExponentialBackoffWithJitter(
         *     shouldReconnect = { failure ->
         *         // Treat our app-defined "kicked" code as fatal too.
         *         if (failure is FailureContext.Closed && failure.code == 4001) false
         *         else DEFAULT_SHOULD_RECONNECT(failure)
         *     },
         * )
         * ```
         */
        val DEFAULT_SHOULD_RECONNECT: (FailureContext) -> Boolean = { failure ->
            when (failure) {
                is FailureContext.Closed -> failure.code !in FATAL_CLOSE_CODES
                is FailureContext.Failure -> failure.httpCode !in FATAL_HTTP_CODES
            }
        }
    }
}
