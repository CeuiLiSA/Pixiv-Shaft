package ceui.pixiv.nana7mi.session

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal data class TestPayload(
    val uid: Long,
    val accessToken: String,
    val refreshToken: String?,
    val isPremium: Boolean?,
    val displayName: String = "owner-$uid",
)

internal class FakeSessionClock(
    initialWallMs: Long = 1_700_000_000_000L,
    initialMonotonicNanos: Long = 1_000_000_000L,
) : SessionClock {
    private val wallMs = AtomicLong(initialWallMs)
    private val monoNanos = AtomicLong(initialMonotonicNanos)

    override fun wallTimeMillis(): Long = wallMs.get()
    override fun monotonicNanos(): Long = monoNanos.get()

    fun advanceMillis(deltaMs: Long) {
        wallMs.addAndGet(deltaMs)
        monoNanos.addAndGet(deltaMs * 1_000_000L)
    }

    fun setWallMillis(value: Long) {
        wallMs.set(value)
    }
}

internal class FakeAccountReportStore(
    var persistOnlineResult: Boolean = true,
    var attemptOnlineResult: ReportDeliveryOutcome = ReportDeliveryOutcome.DELIVERED,
    var persistInvalidResult: Boolean = true,
    var onPersistOnline: (suspend (Long, TestPayload) -> Unit)? = null,
    var onAttemptOnline: (suspend (Long) -> Unit)? = null,
) : AccountReportStore<TestPayload> {
    val persistedOnline = CopyOnWriteArrayList<Pair<Long, TestPayload>>()
    val attemptedOnline = CopyOnWriteArrayList<Long>()
    val persistedInvalid = CopyOnWriteArrayList<Pair<Long, String>>()

    override suspend fun persistOnline(uid: Long, payload: TestPayload): Boolean {
        onPersistOnline?.invoke(uid, payload)
        persistedOnline.add(uid to payload)
        return persistOnlineResult
    }

    override suspend fun attemptOnline(uid: Long): ReportDeliveryOutcome {
        onAttemptOnline?.invoke(uid)
        attemptedOnline.add(uid)
        return attemptOnlineResult
    }

    override suspend fun persistInvalidRefreshToken(uid: Long, refreshToken: String): Boolean {
        persistedInvalid.add(uid to refreshToken)
        return persistInvalidResult
    }
}

internal class RecordingSessionObserver : SessionObserver {
    val events = CopyOnWriteArrayList<SessionEvent>()
    override fun onEvent(event: SessionEvent) {
        events.add(event)
    }
}

internal val TestPayloadUpdater = AccountPayloadUpdater<TestPayload> { previous, tokens, attributedPremium ->
    previous.copy(
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
        isPremium = MembershipPolicy.merge(previous.isPremium, attributedPremium),
    )
}

internal fun testBorrowedAccount(
    uid: Long = 102L,
    accessToken: String = "access-$uid",
    refreshToken: String? = "refresh-$uid",
    updatedAtMs: Long = 1_700_000_000_000L,
    expiresAtMs: Long = updatedAtMs + Nana7miSessionConfig.DEFAULT_TOKEN_VALID_DURATION_MS,
    expired: Boolean = false,
    isPremium: Boolean? = true,
): BorrowedAccount<TestPayload> = BorrowedAccount.create(
    TestPayload(
        uid = uid,
        accessToken = accessToken,
        refreshToken = refreshToken,
        isPremium = isPremium,
    ),
) {
    this.uid = uid
    this.accessToken = accessToken
    this.refreshToken = refreshToken
    this.updatedAtMs = updatedAtMs
    this.expiresAtMs = expiresAtMs
    this.expired = expired
    this.isPremium = isPremium
}

internal fun testRefreshedTokens(
    accessToken: String,
    refreshToken: String,
    expiresInSeconds: Int = 3600,
    reportedUid: Long? = null,
    reportedIsPremium: Boolean? = null,
): RefreshedTokens = RefreshedTokens.create {
    this.accessToken = accessToken
    this.refreshToken = refreshToken
    this.expiresInSeconds = expiresInSeconds
    this.reportedUid = reportedUid
    this.reportedIsPremium = reportedIsPremium
}
