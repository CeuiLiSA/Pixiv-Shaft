package ceui.pixiv.nana7mi.session

import java.io.IOException
import java.util.Objects

/**
 * Immutable snapshot of a borrowed account lease managed by [BorrowedSession].
 *
 * The framework operates strictly on [uid], token/expiry metadata, and [isPremium]; host-specific
 * account data travels untouched inside [payload] (for example, a richer OAuth/user DTO that must
 * survive token rotation when reported back to the backend).
 *
 * Implemented as a non-`data` immutable class with [Builder] / [newBuilder] to preserve binary
 * compatibility across future SDK releases.
 */
public class BorrowedAccount<out A : Any> private constructor(
    public val uid: Long,
    public val accessToken: String,
    public val refreshToken: String?,
    public val updatedAtMs: Long,
    public val expiresAtMs: Long,
    public val expired: Boolean,
    public val isPremium: Boolean?,
    public val payload: A,
) {
    /** Formats the standard HTTP Bearer authorization header value for this lease. */
    public fun authorizationHeader(): String = "Bearer $accessToken"

    /** Returns `true` when [nowWallMs] has reached or passed the local renewal deadline. */
    public fun isLocallyExpired(nowWallMs: Long): Boolean = expiresAtMs <= nowWallMs

    /** Creates a mutable [Builder] initialized with all fields of this snapshot. */
    public fun <B : Any> newBuilder(payload: B): Builder<B> = Builder(payload)
        .uid(uid)
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .updatedAtMs(updatedAtMs)
        .expiresAtMs(expiresAtMs)
        .expired(expired)
        .isPremium(isPremium)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BorrowedAccount<*>) return false
        return uid == other.uid &&
            accessToken == other.accessToken &&
            refreshToken == other.refreshToken &&
            updatedAtMs == other.updatedAtMs &&
            expiresAtMs == other.expiresAtMs &&
            expired == other.expired &&
            isPremium == other.isPremium &&
            payload == other.payload
    }

    override fun hashCode(): Int = Objects.hash(
        uid,
        accessToken,
        refreshToken,
        updatedAtMs,
        expiresAtMs,
        expired,
        isPremium,
        payload,
    )

    /** Redacts token secrets so diagnostic/exception interpolation can never leak credentials. */
    override fun toString(): String =
        "BorrowedAccount(uid=$uid, accessToken=<redacted>, refreshToken=<redacted>, " +
            "updatedAtMs=$updatedAtMs, expiresAtMs=$expiresAtMs, expired=$expired, " +
            "isPremium=$isPremium)"

    @Nana7miDsl
    public class Builder<A : Any>(
        public var payload: A,
    ) {
        public var uid: Long = 0L
        public var accessToken: String = ""
        public var refreshToken: String? = null
        public var updatedAtMs: Long = 0L
        public var expiresAtMs: Long = 0L
        public var expired: Boolean = false
        public var isPremium: Boolean? = null

        public fun uid(uid: Long): Builder<A> = apply { this.uid = uid }
        public fun accessToken(accessToken: String): Builder<A> = apply { this.accessToken = accessToken }
        public fun refreshToken(refreshToken: String?): Builder<A> = apply { this.refreshToken = refreshToken }
        public fun updatedAtMs(updatedAtMs: Long): Builder<A> = apply { this.updatedAtMs = updatedAtMs }
        public fun expiresAtMs(expiresAtMs: Long): Builder<A> = apply { this.expiresAtMs = expiresAtMs }
        public fun expired(expired: Boolean): Builder<A> = apply { this.expired = expired }
        public fun isPremium(isPremium: Boolean?): Builder<A> = apply { this.isPremium = isPremium }

        public fun build(): BorrowedAccount<A> = BorrowedAccount(
            uid = uid,
            accessToken = accessToken,
            refreshToken = refreshToken,
            updatedAtMs = updatedAtMs,
            expiresAtMs = expiresAtMs,
            expired = expired,
            isPremium = isPremium,
            payload = payload,
        )
    }

    public companion object {
        @JvmStatic
        public fun <A : Any> builder(payload: A): Builder<A> = Builder(payload)

        public inline fun <A : Any> create(
            payload: A,
            block: Builder<A>.() -> Unit,
        ): BorrowedAccount<A> = Builder(payload).apply(block).build()
    }
}

/**
 * Token material and optional membership signal returned by [AccountTokenRefresher].
 *
 * Non-`data` immutable class with [Builder] / [newBuilder] for binary compatibility and secret
 * redaction in [toString].
 */
public class RefreshedTokens private constructor(
    public val accessToken: String,
    public val refreshToken: String,
    public val expiresInSeconds: Int,
    /**
     * Account id reported by the OAuth provider in the refresh response, or `null`/non-positive
     * when absent or unparsable. Used by [MembershipPolicy.attribute] to ensure membership updates
     * only apply to the account being renewed.
     */
    public val reportedUid: Long?,
    /**
     * Tri-state membership reported by the OAuth provider:
     * - `true` / `false`: explicitly stated in this refresh response;
     * - `null`: omitted by the provider; stored membership must be preserved verbatim.
     */
    public val reportedIsPremium: Boolean?,
) {
    public fun newBuilder(): Builder = Builder()
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .expiresInSeconds(expiresInSeconds)
        .reportedUid(reportedUid)
        .reportedIsPremium(reportedIsPremium)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RefreshedTokens) return false
        return accessToken == other.accessToken &&
            refreshToken == other.refreshToken &&
            expiresInSeconds == other.expiresInSeconds &&
            reportedUid == other.reportedUid &&
            reportedIsPremium == other.reportedIsPremium
    }

    override fun hashCode(): Int = Objects.hash(
        accessToken,
        refreshToken,
        expiresInSeconds,
        reportedUid,
        reportedIsPremium,
    )

    /** Redacts token secrets so diagnostic/exception interpolation can never leak credentials. */
    override fun toString(): String =
        "RefreshedTokens(accessToken=<redacted>, refreshToken=<redacted>, " +
            "expiresInSeconds=$expiresInSeconds, reportedUid=$reportedUid, " +
            "reportedIsPremium=$reportedIsPremium)"

    @Nana7miDsl
    public class Builder {
        public var accessToken: String = ""
        public var refreshToken: String = ""
        public var expiresInSeconds: Int = 0
        public var reportedUid: Long? = null
        public var reportedIsPremium: Boolean? = null

        public fun accessToken(accessToken: String): Builder = apply { this.accessToken = accessToken }
        public fun refreshToken(refreshToken: String): Builder = apply { this.refreshToken = refreshToken }
        public fun expiresInSeconds(expiresInSeconds: Int): Builder = apply { this.expiresInSeconds = expiresInSeconds }
        public fun reportedUid(reportedUid: Long?): Builder = apply { this.reportedUid = reportedUid }
        public fun reportedIsPremium(reportedIsPremium: Boolean?): Builder = apply { this.reportedIsPremium = reportedIsPremium }

        public fun build(): RefreshedTokens {
            require(accessToken.isNotBlank()) { "refreshed accessToken must not be blank" }
            require(refreshToken.isNotBlank()) { "refreshed refreshToken must not be blank" }
            require(expiresInSeconds >= 0) { "expiresInSeconds must be non-negative" }
            return RefreshedTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresInSeconds = expiresInSeconds,
                reportedUid = reportedUid,
                reportedIsPremium = reportedIsPremium,
            )
        }
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()

        public inline fun create(block: Builder.() -> Unit): RefreshedTokens =
            Builder().apply(block).build()
    }
}

/**
 * Rate-limit or quota rejection metadata attached to an [AcquireResult.RateLimited] outcome.
 *
 * All fields are nullable to tolerate legacy backends or coarse limiters that omit quota details.
 * The SDK deliberately avoids hardcoding backend-specific scope names; callers match scopes via
 * [matchesScope] and compute reset countdowns via [remainingResetMs].
 */
public class RateLimitInfo private constructor(
    public val retryAfterSeconds: Long?,
    public val scope: String?,
    public val resetsAtMs: Long?,
    public val serverTimeMs: Long?,
) {
    /** Returns `true` when [scope] is non-null and present in [scopes]. */
    public fun matchesScope(scopes: Set<String>): Boolean = scope != null && scope in scopes

    /**
     * Computes positive milliseconds until the limit resets, preferring server-anchored
     * `[resetsAtMs] - [serverTimeMs]` over `Retry-After` seconds to avoid client clock skew.
     * Returns `null` when no positive duration can be computed.
     */
    public fun remainingResetMs(): Long? {
        val ms = when {
            resetsAtMs != null && serverTimeMs != null -> resetsAtMs - serverTimeMs
            retryAfterSeconds != null -> retryAfterSeconds * 1_000L
            else -> return null
        }
        return ms.takeIf { it > 0L }
    }

    public fun newBuilder(): Builder = Builder()
        .retryAfterSeconds(retryAfterSeconds)
        .scope(scope)
        .resetsAtMs(resetsAtMs)
        .serverTimeMs(serverTimeMs)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RateLimitInfo) return false
        return retryAfterSeconds == other.retryAfterSeconds &&
            scope == other.scope &&
            resetsAtMs == other.resetsAtMs &&
            serverTimeMs == other.serverTimeMs
    }

    override fun hashCode(): Int = Objects.hash(retryAfterSeconds, scope, resetsAtMs, serverTimeMs)

    override fun toString(): String =
        "RateLimitInfo(retryAfterSeconds=$retryAfterSeconds, scope=$scope, " +
            "resetsAtMs=$resetsAtMs, serverTimeMs=$serverTimeMs)"

    @Nana7miDsl
    public class Builder {
        public var retryAfterSeconds: Long? = null
        public var scope: String? = null
        public var resetsAtMs: Long? = null
        public var serverTimeMs: Long? = null

        public fun retryAfterSeconds(seconds: Long?): Builder = apply { this.retryAfterSeconds = seconds }
        public fun scope(scope: String?): Builder = apply { this.scope = scope }
        public fun resetsAtMs(resetsAtMs: Long?): Builder = apply { this.resetsAtMs = resetsAtMs }
        public fun serverTimeMs(serverTimeMs: Long?): Builder = apply { this.serverTimeMs = serverTimeMs }

        public fun build(): RateLimitInfo = RateLimitInfo(
            retryAfterSeconds = retryAfterSeconds,
            scope = scope,
            resetsAtMs = resetsAtMs,
            serverTimeMs = serverTimeMs,
        )
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()

        public inline fun create(block: Builder.() -> Unit): RateLimitInfo =
            Builder().apply(block).build()
    }
}

/** Why a borrowed account token renewal was triggered. */
public enum class RenewalReason(public val wire: String) {
    /** Dispatcher flagged the returned account lease as already expired. */
    SERVER_EXPIRED("server_expired"),

    /** Local [BorrowedAccount.expiresAtMs] deadline elapsed before sending a request. */
    LOCAL_EXPIRY("local_expiry"),

    /** Upstream rejected the access token per [AuthFailureClassifier.isAccessTokenExpired]. */
    AUTH_REJECTED("auth_rejected"),
}

/** Local pre-flight validation failure before attempting an OAuth refresh. */
public enum class ValidationFailureReason(public val wire: String) {
    MISSING_REFRESH_TOKEN("missing_refresh_token"),
    INVALID_ACCOUNT_UID("missing_account_uid"),
}

/** Cache operation that raised an isolated runtime fault. */
public enum class CacheOperation(public val wire: String) {
    LOOKUP("lookup"),
    STORE("store"),
}

/**
 * Outcome of acquiring or renewing a borrowed account.
 *
 * Cancellation always propagates as [kotlinx.coroutines.CancellationException]; expected policy,
 * lifecycle, pool, HTTP, transport, membership, and payload failures are represented as values.
 */
public sealed interface AcquireResult<out A : Any> {

    /** Stable, unobfuscated label for telemetry, fallback routing, and structured logs. */
    public val reasonLabel: String

    public class Success<out A : Any>(
        public val account: BorrowedAccount<A>,
    ) : AcquireResult<A> {
        override val reasonLabel: String
            get() = if (account.expired) "expired" else "success"

        override fun equals(other: Any?): Boolean = other is Success<*> && account == other.account
        override fun hashCode(): Int = account.hashCode()
        override fun toString(): String = "AcquireResult.Success(account=$account)"
    }

    public sealed interface Failure<out A : Any> : AcquireResult<A>

    /** Acquisition was blocked by [SessionAccessPolicy] before touching any network transport. */
    public class Disabled(
        public val reason: String = DEFAULT_REASON,
    ) : Failure<Nothing> {
        init {
            require(reason.isNotBlank()) { "disabled reason must not be blank" }
        }

        override val reasonLabel: String get() = reason

        override fun equals(other: Any?): Boolean = other is Disabled && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "AcquireResult.Disabled(reason=$reason)"

        public companion object {
            public const val DEFAULT_REASON: String = "disabled"
        }
    }

    /** The owning [Nana7miSessionEngine] has been closed; no network call was attempted. */
    public data object Closed : Failure<Nothing> {
        override val reasonLabel: String get() = "session_engine_closed"
    }

    /** The backend accepted the request but currently has no account available to dispatch. */
    public data object NoAccount : Failure<Nothing> {
        override val reasonLabel: String get() = "no_account"
    }

    /**
     * The account is explicitly no longer a premium member and cannot serve premium-only requests.
     * [payload] travels with the failure so the session can report it back for pool reclassification.
     */
    public class NotPremium<out A : Any>(
        public val uid: Long,
        public val payload: A,
    ) : Failure<A> {
        override val reasonLabel: String get() = "not_premium"

        override fun equals(other: Any?): Boolean =
            other is NotPremium<*> && uid == other.uid && payload == other.payload

        override fun hashCode(): Int = Objects.hash(uid, payload)
        override fun toString(): String = "AcquireResult.NotPremium(uid=$uid)"
    }

    /** The backend rejected the request due to quota exhaustion or rate limiting (HTTP 429). */
    public class RateLimited(
        public val info: RateLimitInfo,
    ) : Failure<Nothing> {
        override val reasonLabel: String get() = "rate_limited"

        override fun equals(other: Any?): Boolean = other is RateLimited && info == other.info
        override fun hashCode(): Int = info.hashCode()
        override fun toString(): String = "AcquireResult.RateLimited(info=$info)"
    }

    /** Non-2xx HTTP response other than 404/429 (for example 409 replay conflict or 5xx). */
    public class HttpFailure(
        public val statusCode: Int,
    ) : Failure<Nothing> {
        override val reasonLabel: String get() = "http_$statusCode"

        override fun equals(other: Any?): Boolean = other is HttpFailure && statusCode == other.statusCode
        override fun hashCode(): Int = statusCode
        override fun toString(): String = "AcquireResult.HttpFailure(statusCode=$statusCode)"
    }

    /** Invalid local input (for example non-positive requester uid). */
    public data object InvalidRequest : Failure<Nothing> {
        override val reasonLabel: String get() = "invalid_request"
    }

    /** Transport-level I/O failure while contacting the dispatcher. */
    public class NetworkFailure(
        public val cause: IOException,
    ) : Failure<Nothing> {
        override val reasonLabel: String get() = "network_failure"

        override fun equals(other: Any?): Boolean = other is NetworkFailure && cause == other.cause
        override fun hashCode(): Int = cause.hashCode()
        override fun toString(): String = "AcquireResult.NetworkFailure(cause=${cause.javaClass.simpleName})"
    }

    /** Malformed response, failed refresh, or failed durable persistence after token rotation. */
    public class InvalidResponse(
        public val cause: Throwable? = null,
    ) : Failure<Nothing> {
        override val reasonLabel: String get() = "invalid_response"

        override fun equals(other: Any?): Boolean = other is InvalidResponse && cause == other.cause
        override fun hashCode(): Int = Objects.hashCode(cause)
        override fun toString(): String =
            "AcquireResult.InvalidResponse(cause=${cause?.javaClass?.simpleName ?: "null"})"
    }
}

/**
 * HTTP 409 is the only status dispatch reserves for a paid first-page request id whose replay
 * window has expired and must be replaced with a fresh request id.
 */
public fun AcquireResult<*>.isPaidRequestIdRejected(): Boolean =
    this is AcquireResult.HttpFailure && statusCode == HTTP_CONFLICT

private const val HTTP_CONFLICT: Int = 409

/** Strongly-typed reason a first-page borrowed flow fell back to an alternative execution path. */
public sealed interface FallbackReason {
    /** Stable wire label suitable for telemetry and structured logs. */
    public val code: String

    /** Borrowing failed with an explicit [AcquireResult.Failure]. */
    public class AcquireFailed(
        public val result: AcquireResult.Failure<*>,
    ) : FallbackReason {
        override val code: String get() = result.reasonLabel

        override fun equals(other: Any?): Boolean = other is AcquireFailed && result == other.result
        override fun hashCode(): Int = result.hashCode()
        override fun toString(): String = "FallbackReason.AcquireFailed(code=$code)"
    }

    /** Dispatch returned a lease that remained flagged as expired after renewal checks. */
    public data object AccountExpired : FallbackReason {
        override val code: String get() = "expired"
    }

    /** Mid-request token renewal failed or membership lapsed ([BorrowedAccountUnavailableException]). */
    public class AccountUnavailable(
        public val cause: BorrowedAccountUnavailableException,
    ) : FallbackReason {
        override val code: String get() = CODE

        override fun equals(other: Any?): Boolean = other is AccountUnavailable && cause == other.cause
        override fun hashCode(): Int = cause.hashCode()
        override fun toString(): String = "FallbackReason.AccountUnavailable(code=$code)"

        public companion object {
            public const val CODE: String = "borrowed_refresh_failed"
        }
    }
}

/** Outcome returned by [AccountTokenRefresher.refresh]. */
public sealed interface TokenRefreshOutcome {
    public class Success(public val tokens: RefreshedTokens) : TokenRefreshOutcome {
        override fun equals(other: Any?): Boolean = other is Success && tokens == other.tokens
        override fun hashCode(): Int = tokens.hashCode()
        override fun toString(): String = "TokenRefreshOutcome.Success(tokens=$tokens)"
    }

    /** Provider definitively rejected the refresh token (e.g. `invalid_grant`). */
    public class InvalidRefreshToken(public val cause: Throwable) : TokenRefreshOutcome {
        override fun equals(other: Any?): Boolean = other is InvalidRefreshToken && cause == other.cause
        override fun hashCode(): Int = cause.hashCode()
        override fun toString(): String = "TokenRefreshOutcome.InvalidRefreshToken(cause=${cause.javaClass.simpleName})"
    }

    /** Transient or unexpected refresh failure that does not prove token revocation. */
    public class Failed(public val cause: Throwable) : TokenRefreshOutcome {
        override fun equals(other: Any?): Boolean = other is Failed && cause == other.cause
        override fun hashCode(): Int = cause.hashCode()
        override fun toString(): String = "TokenRefreshOutcome.Failed(cause=${cause.javaClass.simpleName})"
    }
}

/** Immediate delivery result returned by [AccountReportStore.attemptOnline]. */
public enum class ReportDeliveryOutcome {
    DELIVERED,
    QUEUED,
    FAILED,
}

/**
 * Explicit finite state machine for one [BorrowedSession].
 *
 * Illegal flag combinations (such as having an active account while marked lost, or requiring a
 * paid request id without storing one) are unrepresentable at the type level.
 */
public sealed interface BorrowedSessionState<out A : Any> {

    /** Never borrowed and not backed by a cached premium cursor; pagination uses the caller's own session. */
    public data object Idle : BorrowedSessionState<Nothing>

    /**
     * First page came out of cache under a premium cursor before any account was borrowed.
     * The first pagination cache miss must borrow on demand (offering [paidRequestId] first)
     * and must never continue the cursor with the caller's own account.
     */
    public class CachedCursor(
        public val paidRequestId: String?,
        public val requestIdRequired: Boolean,
    ) : BorrowedSessionState<Nothing> {
        init {
            require(!requestIdRequired || !paidRequestId.isNullOrBlank()) {
                "request-id protocol enabled without a paid first-page id"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is CachedCursor &&
                paidRequestId == other.paidRequestId &&
                requestIdRequired == other.requestIdRequired

        override fun hashCode(): Int = Objects.hash(paidRequestId, requestIdRequired)

        override fun toString(): String =
            "BorrowedSessionState.CachedCursor(paidRequestId=$paidRequestId, requestIdRequired=$requestIdRequired)"
    }

    /** Actively backed by a borrowed account ready for authenticated requests and renewal. */
    public class Active<out A : Any>(
        public val account: BorrowedAccount<A>,
    ) : BorrowedSessionState<A> {
        override fun equals(other: Any?): Boolean = other is Active<*> && account == other.account
        override fun hashCode(): Int = account.hashCode()
        override fun toString(): String = "BorrowedSessionState.Active(account=$account)"
    }

    /**
     * Terminal state once a borrowed account or cached premium cursor became unusable.
     * Once entered, this state can never transition back to [Active] or [CachedCursor].
     * Pagination must terminate cleanly rather than retrying a premium cursor with the caller's own account.
     */
    public data object Lost : BorrowedSessionState<Nothing>
}

/** Why borrowed pagination ended early without issuing a direct-account request. */
public enum class PaginationEndReason(public val wire: String) {
    ALREADY_LOST("already_lost"),
    RENEWAL_FAILED("renew_failed"),
}

/**
 * Thrown when an active borrowed account cannot be renewed (or a cached premium cursor cannot
 * acquire an account). Only this exception is eligible for a first-page fallback or clean
 * pagination termination; ordinary upstream errors propagate unchanged.
 */
public class BorrowedAccountUnavailableException(
    cause: Throwable,
) : IOException("borrowed account could not be acquired or renewed", cause)

/** Thrown when attempting to create a new [BorrowedSession] from a closed [Nana7miSessionEngine]. */
public class SessionEngineClosedException :
    IllegalStateException("Nana7miSessionEngine has been closed")

/** Returns `true` if this throwable or any cause in its cycle-safe chain is [BorrowedAccountUnavailableException]. */
public fun Throwable.isBorrowedAccountUnavailable(): Boolean =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .any { it is BorrowedAccountUnavailableException }
