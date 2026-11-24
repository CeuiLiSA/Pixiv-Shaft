package ceui.pixiv.nana7mi.session

import ceui.pixiv.nana7mi.session.internal.BoundedNetworkRetry
import ceui.pixiv.nana7mi.session.internal.SafeSessionObserver
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Objects
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/**
 * Stable, unobfuscated transport failure buckets shared across retry policy and observability.
 */
public enum class TransportFailureKind(
    public val wire: String,
    public val retryable: Boolean,
) {
    CANCELLED("network_cancelled", false),
    TIMEOUT("network_timeout", true),
    DNS("network_dns", true),
    TLS("network_tls", false),
    CONNECT("network_connect", true),
    SOCKET("network_socket", true),
    CRONET("network_cronet", true),
    IO("network_io", true),
}

/** Classifies a throwable (walking its cause chain) into a [TransportFailureKind], or `null` if non-transport. */
public fun interface TransportFailureClassifier {
    public fun classify(error: Throwable): TransportFailureKind?

    public companion object {
        /**
         * Default JVM classifier matching HTTP stacks (OkHttp/Retrofit/Cronet/JDK sockets).
         *
         * Precedence is intentional: [SocketTimeoutException] is a subtype of
         * [InterruptedIOException], and specific DNS/TLS/connect/socket exceptions are subtypes
         * of [IOException].
         */
        @JvmField
        public val DEFAULT: TransportFailureClassifier = TransportFailureClassifier { error ->
            val causes = generateSequence(error) { current ->
                current.cause?.takeUnless { it === current }
            }.toList()
            when {
                causes.any { it is CancellationException } -> TransportFailureKind.CANCELLED
                causes.any { it is SocketTimeoutException } -> TransportFailureKind.TIMEOUT
                causes.any { it is UnknownHostException } -> TransportFailureKind.DNS
                causes.any { it is SSLException } -> TransportFailureKind.TLS
                causes.any { it is ConnectException || it is NoRouteToHostException } ->
                    TransportFailureKind.CONNECT
                causes.any { it is SocketException } -> TransportFailureKind.SOCKET
                causes.any { it.javaClass.name.startsWith("org.chromium.net.") } ->
                    TransportFailureKind.CRONET
                causes.any { it is InterruptedIOException } -> TransportFailureKind.CANCELLED
                causes.any { it is IOException } -> TransportFailureKind.IO
                else -> null
            }
        }
    }
}

/**
 * Immutable bounded-budget retry configuration for idempotent network operations.
 *
 * The total time budget is measured from the start of the first attempt, so a slow first failure
 * cannot open a fresh full-length retry timeout. Fast DNS/connect/socket failures are retried
 * within the remaining window; non-retryable failures (TLS, cancellation, non-IO) fail fast.
 *
 * Implemented as a non-`data` immutable class with [Builder] / [newBuilder] to preserve binary
 * compatibility across future SDK releases.
 */
public class NetworkRetryPolicy private constructor(
    public val totalBudgetMs: Long,
    public val retryDelayMs: Long,
    public val minRetryWindowMs: Long,
    public val maxRetries: Int,
) {
    public fun newBuilder(): Builder = Builder()
        .totalBudgetMs(totalBudgetMs)
        .retryDelayMs(retryDelayMs)
        .minRetryWindowMs(minRetryWindowMs)
        .maxRetries(maxRetries)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NetworkRetryPolicy) return false
        return totalBudgetMs == other.totalBudgetMs &&
            retryDelayMs == other.retryDelayMs &&
            minRetryWindowMs == other.minRetryWindowMs &&
            maxRetries == other.maxRetries
    }

    override fun hashCode(): Int = Objects.hash(totalBudgetMs, retryDelayMs, minRetryWindowMs, maxRetries)

    override fun toString(): String =
        "NetworkRetryPolicy(totalBudgetMs=$totalBudgetMs, retryDelayMs=$retryDelayMs, " +
            "minRetryWindowMs=$minRetryWindowMs, maxRetries=$maxRetries)"

    @Nana7miDsl
    public class Builder {
        public var totalBudgetMs: Long = DEFAULT_BUDGET_MS
        public var retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS
        public var minRetryWindowMs: Long = DEFAULT_MIN_RETRY_WINDOW_MS
        public var maxRetries: Int = DEFAULT_MAX_RETRIES

        public fun totalBudgetMs(ms: Long): Builder = apply { this.totalBudgetMs = ms }
        public fun retryDelayMs(ms: Long): Builder = apply { this.retryDelayMs = ms }
        public fun minRetryWindowMs(ms: Long): Builder = apply { this.minRetryWindowMs = ms }
        public fun maxRetries(count: Int): Builder = apply { this.maxRetries = count }

        public fun build(): NetworkRetryPolicy {
            require(totalBudgetMs > 0L) { "totalBudgetMs must be positive" }
            require(retryDelayMs >= 0L) { "retryDelayMs must be non-negative" }
            require(minRetryWindowMs >= 0L) { "minRetryWindowMs must be non-negative" }
            require(maxRetries >= 0) { "maxRetries must be non-negative" }
            return NetworkRetryPolicy(
                totalBudgetMs = totalBudgetMs,
                retryDelayMs = retryDelayMs,
                minRetryWindowMs = minRetryWindowMs,
                maxRetries = maxRetries,
            )
        }
    }

    public companion object {
        public const val DEFAULT_BUDGET_MS: Long = 15_000L
        public const val DEFAULT_RETRY_DELAY_MS: Long = 250L
        public const val DEFAULT_MIN_RETRY_WINDOW_MS: Long = 1_000L
        public const val DEFAULT_MAX_RETRIES: Int = 1

        @JvmField
        public val DEFAULT: NetworkRetryPolicy = Builder().build()

        @JvmField
        public val NONE: NetworkRetryPolicy = Builder().maxRetries(0).build()

        @JvmStatic
        public fun builder(): Builder = Builder()

        public inline fun create(block: Builder.() -> Unit): NetworkRetryPolicy =
            Builder().apply(block).build()
    }
}

/**
 * Executes idempotent suspending network operations under a [NetworkRetryPolicy].
 */
public interface NetworkRetryExecutor {
    public val defaultPolicy: NetworkRetryPolicy

    /** Retries throwing idempotent calls up to [policy] budget. Always propagates cancellation. */
    public suspend fun <T> execute(
        stage: String,
        policy: NetworkRetryPolicy = defaultPolicy,
        source: suspend () -> T,
    ): T

    /** Result-returning twin for APIs that convert transport exceptions into explicit values. */
    public suspend fun <T> executeResult(
        stage: String,
        policy: NetworkRetryPolicy = defaultPolicy,
        failureOf: (T) -> Throwable?,
        source: suspend () -> T,
    ): T
}

/** Creates a standalone [NetworkRetryExecutor] backed by the given policy, classifier, clock, and observer. */
@JvmOverloads
public fun NetworkRetryExecutor(
    defaultPolicy: NetworkRetryPolicy = NetworkRetryPolicy.DEFAULT,
    classifier: TransportFailureClassifier = TransportFailureClassifier.DEFAULT,
    clock: SessionClock = SessionClock.SYSTEM,
    observer: SessionObserver = SessionObserver.NO_OP,
): NetworkRetryExecutor = BoundedNetworkRetry(
    defaultPolicy = defaultPolicy,
    classifier = classifier,
    clock = clock,
    observer = SafeSessionObserver(observer),
)
