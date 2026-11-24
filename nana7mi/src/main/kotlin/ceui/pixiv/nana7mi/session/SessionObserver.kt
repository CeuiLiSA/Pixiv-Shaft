package ceui.pixiv.nana7mi.session

import java.util.Objects
import kotlinx.coroutines.CancellationException

/**
 * Metadata-only diagnostic and lifecycle observer for the Nana7mi session engine.
 *
 * Events NEVER include raw access tokens, refresh tokens, or account payloads. Observer callbacks
 * are invoked outside internal locks and isolated from core control flow; implementations must not
 * block.
 */
public fun interface SessionObserver {
    public fun onEvent(event: SessionEvent)

    public companion object {
        @JvmField
        public val NO_OP: SessionObserver = SessionObserver {}

        /** Combines multiple observers into one fan-out observer (Composite pattern). */
        @JvmStatic
        public fun composite(vararg observers: SessionObserver): SessionObserver {
            val active = observers.filter { it !== NO_OP }
            if (active.isEmpty()) return NO_OP
            if (active.size == 1) return active[0]
            return SessionObserver { event ->
                for (observer in active) {
                    try {
                        observer.onEvent(event)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        // Individual observer isolation; fatal JVM Errors and cancellation still propagate.
                    }
                }
            }
        }
    }
}

/** Metadata-only summary of a dispatch attempt for [SessionEvent.FetchCompleted]. */
public sealed interface FetchEventOutcome {
    public class Success(
        public val accountUid: Long,
        public val expired: Boolean,
        public val updatedAtMs: Long,
        public val expiresAtMs: Long,
        public val ageMs: Long,
        public val remainingMs: Long,
    ) : FetchEventOutcome {
        override fun equals(other: Any?): Boolean = other is Success &&
            accountUid == other.accountUid &&
            expired == other.expired &&
            updatedAtMs == other.updatedAtMs &&
            expiresAtMs == other.expiresAtMs &&
            ageMs == other.ageMs &&
            remainingMs == other.remainingMs

        override fun hashCode(): Int = Objects.hash(accountUid, expired, updatedAtMs, expiresAtMs, ageMs, remainingMs)
        override fun toString(): String =
            "FetchEventOutcome.Success(accountUid=$accountUid, expired=$expired, ageMs=$ageMs, remainingMs=$remainingMs)"
    }

    public class Disabled(public val reason: String) : FetchEventOutcome {
        override fun equals(other: Any?): Boolean = other is Disabled && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "FetchEventOutcome.Disabled(reason=$reason)"
    }

    public data object Closed : FetchEventOutcome
    public data object NoAccount : FetchEventOutcome

    public class NotPremium(public val accountUid: Long) : FetchEventOutcome {
        override fun equals(other: Any?): Boolean = other is NotPremium && accountUid == other.accountUid
        override fun hashCode(): Int = accountUid.hashCode()
        override fun toString(): String = "FetchEventOutcome.NotPremium(accountUid=$accountUid)"
    }

    public class RateLimited(public val info: RateLimitInfo) : FetchEventOutcome {
        override fun equals(other: Any?): Boolean = other is RateLimited && info == other.info
        override fun hashCode(): Int = info.hashCode()
        override fun toString(): String = "FetchEventOutcome.RateLimited(info=$info)"
    }

    public class HttpFailure(public val statusCode: Int) : FetchEventOutcome {
        override fun equals(other: Any?): Boolean = other is HttpFailure && statusCode == other.statusCode
        override fun hashCode(): Int = statusCode
        override fun toString(): String = "FetchEventOutcome.HttpFailure(statusCode=$statusCode)"
    }

    public data object InvalidRequest : FetchEventOutcome

    public class NetworkFailure(public val cause: Throwable) : FetchEventOutcome {
        override fun equals(other: Any?): Boolean = other is NetworkFailure && cause == other.cause
        override fun hashCode(): Int = cause.hashCode()
        override fun toString(): String = "FetchEventOutcome.NetworkFailure(cause=${cause.javaClass.simpleName})"
    }

    public class InvalidResponse(public val cause: Throwable?) : FetchEventOutcome {
        override fun equals(other: Any?): Boolean = other is InvalidResponse && cause == other.cause
        override fun hashCode(): Int = Objects.hashCode(cause)
        override fun toString(): String =
            "FetchEventOutcome.InvalidResponse(cause=${cause?.javaClass?.simpleName ?: "null"})"
    }
}

/** Structured, metadata-only diagnostic and lifecycle events emitted by the session engine. */
public sealed interface SessionEvent {

    public class SerialPermitAcquired(
        public val stage: String,
        public val waitedMs: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is SerialPermitAcquired && stage == other.stage && waitedMs == other.waitedMs

        override fun hashCode(): Int = Objects.hash(stage, waitedMs)
        override fun toString(): String = "SerialPermitAcquired(stage=$stage, waitedMs=$waitedMs)"
    }

    public class SerialPermitReleased(
        public val stage: String,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is SerialPermitReleased && stage == other.stage
        override fun hashCode(): Int = stage.hashCode()
        override fun toString(): String = "SerialPermitReleased(stage=$stage)"
    }

    public class RetryScheduled(
        public val stage: String,
        public val failureKind: TransportFailureKind,
        public val attempt: Int,
        public val remainingBudgetMs: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is RetryScheduled &&
            stage == other.stage &&
            failureKind == other.failureKind &&
            attempt == other.attempt &&
            remainingBudgetMs == other.remainingBudgetMs

        override fun hashCode(): Int = Objects.hash(stage, failureKind, attempt, remainingBudgetMs)
        override fun toString(): String =
            "RetryScheduled(stage=$stage, failureKind=$failureKind, attempt=$attempt, remainingBudgetMs=$remainingBudgetMs)"
    }

    public class FetchStarted(
        public val requesterUid: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is FetchStarted && requesterUid == other.requesterUid
        override fun hashCode(): Int = requesterUid.hashCode()
        override fun toString(): String = "FetchStarted(requesterUid=$requesterUid)"
    }

    public class FetchCompleted(
        public val requesterUid: Long,
        public val outcome: FetchEventOutcome,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is FetchCompleted && requesterUid == other.requesterUid && outcome == other.outcome

        override fun hashCode(): Int = Objects.hash(requesterUid, outcome)
        override fun toString(): String = "FetchCompleted(requesterUid=$requesterUid, outcome=$outcome)"
    }

    public class PaidRequestIdRejected(
        public val requesterUid: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is PaidRequestIdRejected && requesterUid == other.requesterUid
        override fun hashCode(): Int = requesterUid.hashCode()
        override fun toString(): String = "PaidRequestIdRejected(requesterUid=$requesterUid)"
    }

    public class AccountReadyCurrent(
        public val accountUid: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is AccountReadyCurrent && accountUid == other.accountUid
        override fun hashCode(): Int = accountUid.hashCode()
        override fun toString(): String = "AccountReadyCurrent(accountUid=$accountUid)"
    }

    public class RenewalStarted(
        public val accountUid: Long,
        public val reason: RenewalReason,
        public val expiredAtMs: Long,
        public val overdueMs: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is RenewalStarted &&
            accountUid == other.accountUid &&
            reason == other.reason &&
            expiredAtMs == other.expiredAtMs &&
            overdueMs == other.overdueMs

        override fun hashCode(): Int = Objects.hash(accountUid, reason, expiredAtMs, overdueMs)
        override fun toString(): String =
            "RenewalStarted(accountUid=$accountUid, reason=$reason, overdueMs=$overdueMs)"
    }

    public class RenewalValidationFailed(
        public val accountUid: Long,
        public val reason: ValidationFailureReason,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is RenewalValidationFailed && accountUid == other.accountUid && reason == other.reason

        override fun hashCode(): Int = Objects.hash(accountUid, reason)
        override fun toString(): String = "RenewalValidationFailed(accountUid=$accountUid, reason=$reason)"
    }

    public class TokenRefreshStarted(
        public val accountUid: Long,
        public val reason: RenewalReason,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is TokenRefreshStarted && accountUid == other.accountUid && reason == other.reason

        override fun hashCode(): Int = Objects.hash(accountUid, reason)
        override fun toString(): String = "TokenRefreshStarted(accountUid=$accountUid, reason=$reason)"
    }

    public class TokenRefreshSucceeded(
        public val accountUid: Long,
        public val reason: RenewalReason,
        public val expiresInSeconds: Int,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is TokenRefreshSucceeded &&
            accountUid == other.accountUid &&
            reason == other.reason &&
            expiresInSeconds == other.expiresInSeconds

        override fun hashCode(): Int = Objects.hash(accountUid, reason, expiresInSeconds)
        override fun toString(): String =
            "TokenRefreshSucceeded(accountUid=$accountUid, reason=$reason, expiresInSeconds=$expiresInSeconds)"
    }

    public class TokenRefreshInvalidGrant(
        public val accountUid: Long,
        public val reason: RenewalReason,
        public val invalidationQueued: Boolean,
        public val cause: Throwable,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is TokenRefreshInvalidGrant &&
            accountUid == other.accountUid &&
            reason == other.reason &&
            invalidationQueued == other.invalidationQueued &&
            cause == other.cause

        override fun hashCode(): Int = Objects.hash(accountUid, reason, invalidationQueued, cause)
        override fun toString(): String =
            "TokenRefreshInvalidGrant(accountUid=$accountUid, reason=$reason, invalidationQueued=$invalidationQueued)"
    }

    public class TokenRefreshFailed(
        public val accountUid: Long,
        public val reason: RenewalReason,
        public val cause: Throwable,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is TokenRefreshFailed &&
            accountUid == other.accountUid &&
            reason == other.reason &&
            cause == other.cause

        override fun hashCode(): Int = Objects.hash(accountUid, reason, cause)
        override fun toString(): String =
            "TokenRefreshFailed(accountUid=$accountUid, reason=$reason, cause=${cause.javaClass.simpleName})"
    }

    public class OnlineReportPersistFailed(
        public val accountUid: Long,
        public val reason: RenewalReason,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is OnlineReportPersistFailed && accountUid == other.accountUid && reason == other.reason

        override fun hashCode(): Int = Objects.hash(accountUid, reason)
        override fun toString(): String = "OnlineReportPersistFailed(accountUid=$accountUid, reason=$reason)"
    }

    public class OnlineReportStarted(
        public val accountUid: Long,
        public val reason: RenewalReason,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is OnlineReportStarted && accountUid == other.accountUid && reason == other.reason

        override fun hashCode(): Int = Objects.hash(accountUid, reason)
        override fun toString(): String = "OnlineReportStarted(accountUid=$accountUid, reason=$reason)"
    }

    public class OnlineReportCompleted(
        public val accountUid: Long,
        public val reason: RenewalReason,
        public val outcome: ReportDeliveryOutcome,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is OnlineReportCompleted &&
            accountUid == other.accountUid &&
            reason == other.reason &&
            outcome == other.outcome

        override fun hashCode(): Int = Objects.hash(accountUid, reason, outcome)
        override fun toString(): String =
            "OnlineReportCompleted(accountUid=$accountUid, reason=$reason, outcome=$outcome)"
    }

    public class NotPremiumDetected(
        public val accountUid: Long,
        public val renewalReason: RenewalReason?,
        public val reportOutcome: String,
    ) : SessionEvent {
        /** `true` when discovered immediately on dispatch; `false` when discovered during renewal. */
        public val fromDispatch: Boolean get() = renewalReason == null

        override fun equals(other: Any?): Boolean = other is NotPremiumDetected &&
            accountUid == other.accountUid &&
            renewalReason == other.renewalReason &&
            reportOutcome == other.reportOutcome

        override fun hashCode(): Int = Objects.hash(accountUid, renewalReason, reportOutcome)
        override fun toString(): String =
            "NotPremiumDetected(accountUid=$accountUid, renewalReason=$renewalReason, reportOutcome=$reportOutcome)"
    }

    public class RenewalSucceeded(
        public val accountUid: Long,
        public val updatedAtMs: Long,
        public val expiresAtMs: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is RenewalSucceeded &&
            accountUid == other.accountUid &&
            updatedAtMs == other.updatedAtMs &&
            expiresAtMs == other.expiresAtMs

        override fun hashCode(): Int = Objects.hash(accountUid, updatedAtMs, expiresAtMs)
        override fun toString(): String =
            "RenewalSucceeded(accountUid=$accountUid, updatedAtMs=$updatedAtMs, expiresAtMs=$expiresAtMs)"
    }

    public class LocalExpiryRefreshTriggered(
        public val stage: String,
        public val accountUid: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is LocalExpiryRefreshTriggered && stage == other.stage && accountUid == other.accountUid

        override fun hashCode(): Int = Objects.hash(stage, accountUid)
        override fun toString(): String = "LocalExpiryRefreshTriggered(stage=$stage, accountUid=$accountUid)"
    }

    public class AuthRejectedRetryTriggered(
        public val stage: String,
        public val accountUid: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is AuthRejectedRetryTriggered && stage == other.stage && accountUid == other.accountUid

        override fun hashCode(): Int = Objects.hash(stage, accountUid)
        override fun toString(): String = "AuthRejectedRetryTriggered(stage=$stage, accountUid=$accountUid)"
    }

    public class RequestSucceeded(
        public val stage: String,
        public val accountUid: Long,
        public val details: String?,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is RequestSucceeded &&
            stage == other.stage &&
            accountUid == other.accountUid &&
            details == other.details

        override fun hashCode(): Int = Objects.hash(stage, accountUid, details)
        override fun toString(): String = "RequestSucceeded(stage=$stage, accountUid=$accountUid)"
    }

    public class RequestFailed(
        public val stage: String,
        public val accountUid: Long,
        public val cause: Throwable,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is RequestFailed && stage == other.stage && accountUid == other.accountUid && cause == other.cause

        override fun hashCode(): Int = Objects.hash(stage, accountUid, cause)
        override fun toString(): String =
            "RequestFailed(stage=$stage, accountUid=$accountUid, cause=${cause.javaClass.simpleName})"
    }

    public class CachedCursorBorrowStarted(
        public val stage: String,
        public val requesterUid: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is CachedCursorBorrowStarted && stage == other.stage && requesterUid == other.requesterUid

        override fun hashCode(): Int = Objects.hash(stage, requesterUid)
        override fun toString(): String = "CachedCursorBorrowStarted(stage=$stage, requesterUid=$requesterUid)"
    }

    public class BorrowedRequestStarted(
        public val stage: String,
        public val accountUid: Long,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is BorrowedRequestStarted && stage == other.stage && accountUid == other.accountUid

        override fun hashCode(): Int = Objects.hash(stage, accountUid)
        override fun toString(): String = "BorrowedRequestStarted(stage=$stage, accountUid=$accountUid)"
    }

    public class PaginationEnded(
        public val stage: String,
        public val reason: PaginationEndReason,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is PaginationEnded && stage == other.stage && reason == other.reason

        override fun hashCode(): Int = Objects.hash(stage, reason)
        override fun toString(): String = "PaginationEnded(stage=$stage, reason=$reason)"
    }

    public class CacheOperationFailed(
        public val stage: String,
        public val operation: CacheOperation,
        public val cause: Throwable,
    ) : SessionEvent {
        override fun equals(other: Any?): Boolean = other is CacheOperationFailed &&
            stage == other.stage &&
            operation == other.operation &&
            cause == other.cause

        override fun hashCode(): Int = Objects.hash(stage, operation, cause)
        override fun toString(): String =
            "CacheOperationFailed(stage=$stage, operation=$operation, cause=${cause.javaClass.simpleName})"
    }

    public data object EngineClosed : SessionEvent
}
