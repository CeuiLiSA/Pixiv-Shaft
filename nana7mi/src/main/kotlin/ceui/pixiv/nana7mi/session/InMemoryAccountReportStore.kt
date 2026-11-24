package ceui.pixiv.nana7mi.session

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory reference implementation of [AccountReportStore].
 *
 * Suitable for prototyping, CLI/server usage with a custom [onDeliver] callback, and deterministic
 * unit tests. Matches the production outbox security contract: invalid refresh tokens are stored
 * strictly as lowercase SHA-256 hex digests, never as plaintext secrets, and persisting a fresh
 * online payload clears any stale invalidation entry for the same `uid`.
 */
public class InMemoryAccountReportStore<A : Any> @JvmOverloads public constructor(
    private val onDeliver: (suspend (uid: Long, payload: A) -> ReportDeliveryOutcome)? = null,
) : AccountReportStore<A> {

    private val onlinePayloads: ConcurrentHashMap<Long, A> = ConcurrentHashMap()
    private val invalidRefreshHashes: ConcurrentHashMap<Long, String> = ConcurrentHashMap()

    override suspend fun persistOnline(uid: Long, payload: A): Boolean {
        if (uid <= 0L) return false
        onlinePayloads[uid] = payload
        invalidRefreshHashes.remove(uid)
        return true
    }

    override suspend fun attemptOnline(uid: Long): ReportDeliveryOutcome {
        val payload = onlinePayloads[uid] ?: return ReportDeliveryOutcome.QUEUED
        val deliver = onDeliver ?: return ReportDeliveryOutcome.DELIVERED
        return deliver(uid, payload)
    }

    override suspend fun persistInvalidRefreshToken(uid: Long, refreshToken: String): Boolean {
        if (uid <= 0L || refreshToken.isBlank()) return false
        invalidRefreshHashes[uid] = sha256Hex(refreshToken)
        return true
    }

    /** Returns the latest persisted online payload for [uid], or `null` if none. */
    public fun latestOnlinePayload(uid: Long): A? = onlinePayloads[uid]

    /** Returns the SHA-256 hex hash of the quarantined refresh token for [uid], or `null` if none. */
    public fun quarantinedTokenHash(uid: Long): String? = invalidRefreshHashes[uid]

    /** Snapshot of all currently persisted online payloads keyed by `uid`. */
    public fun snapshotOnlinePayloads(): Map<Long, A> = onlinePayloads.toMap()

    /** Snapshot of all currently quarantined refresh-token SHA-256 hashes keyed by `uid`. */
    public fun snapshotQuarantinedHashes(): Map<Long, String> = invalidRefreshHashes.toMap()

    /** Clears all in-memory records. */
    public fun clear() {
        onlinePayloads.clear()
        invalidRefreshHashes.clear()
    }

    private companion object {
        fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
