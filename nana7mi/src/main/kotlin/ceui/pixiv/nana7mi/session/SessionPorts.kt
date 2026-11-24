package ceui.pixiv.nana7mi.session

import java.util.UUID as JavaUuid

/**
 * Clock abstraction combining wall-clock time (for token expiry deadlines and timestamps)
 * and monotonic time (for elapsed retry budgets and serial wait durations).
 */
public interface SessionClock {
    public fun wallTimeMillis(): Long
    public fun monotonicNanos(): Long

    public companion object {
        @JvmField
        public val SYSTEM: SessionClock = object : SessionClock {
            override fun wallTimeMillis(): Long = System.currentTimeMillis()
            override fun monotonicNanos(): Long = System.nanoTime()
        }
    }
}

/** Generates unique idempotency request identifiers shared across cache lookup, dispatch, and telemetry. */
public fun interface RequestIdGenerator {
    public fun nextId(): String

    public companion object {
        @JvmField
        public val UUID: RequestIdGenerator = RequestIdGenerator { JavaUuid.randomUUID().toString() }
    }
}

/** Result of evaluating whether borrowed-session acquisition is permitted in the current build/runtime. */
public sealed interface AccessDecision {
    public data object Allowed : AccessDecision

    public class Denied(public val reason: String) : AccessDecision {
        init {
            require(reason.isNotBlank()) { "denial reason must not be blank" }
        }

        override fun equals(other: Any?): Boolean = other is Denied && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "AccessDecision.Denied(reason=$reason)"
    }
}

/**
 * Defense-in-depth gate checked before any account dispatch leaves the device
 * (for example, compile-time Lite flavor isolation).
 */
public fun interface SessionAccessPolicy {
    public fun evaluate(): AccessDecision

    public companion object {
        @JvmField
        public val ALWAYS_ALLOWED: SessionAccessPolicy = SessionAccessPolicy { AccessDecision.Allowed }

        @JvmStatic
        public fun denied(reason: String): SessionAccessPolicy {
            val decision = AccessDecision.Denied(reason)
            return SessionAccessPolicy { decision }
        }
    }
}

/**
 * Backend port that dispatches one borrowed account for [requesterUid].
 *
 * Implementations must map HTTP/network/malformed errors into [AcquireResult] values and let
 * [kotlinx.coroutines.CancellationException] propagate unchanged.
 */
public fun interface AccountDispatcher<A : Any> {
    public suspend fun dispatch(requesterUid: Long, requestId: String?): AcquireResult<A>
}

/**
 * OAuth port that rotates an expired borrowed account's refresh token without touching any
 * logged-in application session.
 */
public fun interface AccountTokenRefresher {
    public suspend fun refresh(uid: Long, refreshToken: String): TokenRefreshOutcome
}

/**
 * Pure projection strategy that folds rotated [RefreshedTokens] and an [attributedPremium]
 * decision (already validated by [MembershipPolicy.attribute]) into the host's opaque payload [A].
 *
 * Implementations should only update token fields and membership (when [attributedPremium] != null),
 * preserving all richer metadata already stored on [previous].
 */
public fun interface AccountPayloadUpdater<A : Any> {
    public fun applyRefresh(
        previous: A,
        tokens: RefreshedTokens,
        attributedPremium: Boolean?,
    ): A
}

/**
 * Durable outbox port for reporting refreshed accounts and quarantining revoked refresh tokens.
 *
 * [persistOnline] and [persistInvalidRefreshToken] are invoked by the framework inside a
 * `NonCancellable` context immediately after token rotation / invalid-grant detection.
 */
public interface AccountReportStore<A : Any> {
    /** Synchronously/durably commits [payload] before any cancellable delivery attempt. */
    public suspend fun persistOnline(uid: Long, payload: A): Boolean

    /** Attempts one immediate delivery of the persisted payload; failures remain durably queued. */
    public suspend fun attemptOnline(uid: Long): ReportDeliveryOutcome

    /** Durably records an invalid-refresh-token quarantine notice without logging raw token secrets. */
    public suspend fun persistInvalidRefreshToken(uid: Long, refreshToken: String): Boolean
}

/**
 * Strategy that recognizes when an upstream API failure indicates an expired OAuth access token
 * eligible for a single refresh-and-replay cycle.
 */
public fun interface AuthFailureClassifier {
    public fun isAccessTokenExpired(error: Throwable): Boolean

    public companion object {
        @JvmField
        public val NEVER: AuthFailureClassifier = AuthFailureClassifier { false }
    }
}

/**
 * Optional cache-aside port for one borrowed page operation (first page or pagination).
 *
 * [lookup] must never fail the caller's request (return `null` on miss or error, while still
 * propagating [kotlinx.coroutines.CancellationException]). [store] is invoked only after a live
 * borrowed-account request succeeds — never for fallback or direct-account responses.
 */
public interface BorrowedPageCache<T : Any> {
    public suspend fun lookup(requestId: String?): T?
    public fun store(page: T)
}
