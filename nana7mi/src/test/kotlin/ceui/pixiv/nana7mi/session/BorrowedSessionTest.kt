package ceui.pixiv.nana7mi.session

import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BorrowedSessionTest {

    private class OAuthExpiredException : RuntimeException("oauth expired")

    private fun buildEngine(
        clock: FakeSessionClock = FakeSessionClock(),
        reportStore: AccountReportStore<TestPayload> = FakeAccountReportStore(),
        observer: SessionObserver = SessionObserver.NO_OP,
        accessPolicy: SessionAccessPolicy = SessionAccessPolicy.ALWAYS_ALLOWED,
        retryPolicy: NetworkRetryPolicy = NetworkRetryPolicy.create { retryDelayMs = 0L },
        requestIds: List<String> = listOf("req-1", "req-2", "req-3"),
        dispatcher: AccountDispatcher<TestPayload>,
        refresher: AccountTokenRefresher = AccountTokenRefresher { _, _ ->
            error("unexpected refresh call")
        },
    ): Nana7miSessionEngine<TestPayload> {
        val idIndex = AtomicInteger(0)
        return Nana7miSessionEngine.create {
            this.dispatcher = dispatcher
            this.tokenRefresher = refresher
            this.payloadUpdater = TestPayloadUpdater
            this.reportStore = reportStore
            this.authFailureClassifier = AuthFailureClassifier { it is OAuthExpiredException }
            this.accessPolicy = accessPolicy
            this.retryPolicy = retryPolicy
            this.requestIdGenerator = RequestIdGenerator {
                requestIds.getOrElse(idIndex.getAndIncrement()) { "req-extra" }
            }
            this.clock = clock
            this.observer = observer
        }
    }

    @Test
    fun `disabled access policy returns Disabled without contacting dispatcher`() = runTest {
        var dispatchCalls = 0
        val engine = buildEngine(
            accessPolicy = SessionAccessPolicy.denied("disabled_for_lite"),
            dispatcher = { _, _ ->
                dispatchCalls++
                AcquireResult.NoAccount
            },
        )
        val session = engine.newSession()

        val result = session.acquireReady(requesterUid = 31660292L, requestId = "r1")

        assertTrue(result is AcquireResult.Disabled)
        assertEquals("disabled_for_lite", result.reasonLabel)
        assertEquals(0, dispatchCalls)
        assertEquals(BorrowedSessionState.Idle, session.state)
    }

    @Test
    fun `closing engine rejects newSession and short-circuits existing sessions with Closed`() = runTest {
        var dispatchCalls = 0
        val observer = RecordingSessionObserver()
        val engine = buildEngine(
            observer = observer,
            dispatcher = { _, _ ->
                dispatchCalls++
                AcquireResult.NoAccount
            },
        )
        val existingSession = engine.newSession()
        assertFalse(engine.isClosed)

        engine.close()
        engine.close() // idempotent
        assertTrue(engine.isClosed)
        assertEquals(1, observer.events.filterIsInstance<SessionEvent.EngineClosed>().size)

        val newSessionError = runCatching { engine.newSession() }.exceptionOrNull()
        assertTrue(newSessionError is SessionEngineClosedException)

        val result = existingSession.acquireReady(requesterUid = 1L, requestId = "r1")
        assertEquals(AcquireResult.Closed, result)
        assertEquals("session_engine_closed", result.reasonLabel)
        assertEquals(0, dispatchCalls)
    }

    @Test
    fun `legacy null requestId dispatch is never retried on transient transport failure`() = runTest {
        var dispatchCalls = 0
        val engine = buildEngine(
            dispatcher = { _, _ ->
                dispatchCalls++
                AcquireResult.NetworkFailure(ConnectException("refused"))
            },
        )
        val session = engine.newSession()

        val result = session.acquireReady(requesterUid = 1L, requestId = null)

        assertTrue(result is AcquireResult.NetworkFailure)
        assertEquals(1, dispatchCalls)
    }

    @Test
    fun `idempotent dispatch retries once with same requestId on transient network failure`() = runTest {
        val seenIds = mutableListOf<String?>()
        val account = testBorrowedAccount(uid = 102L, expired = false)
        val engine = buildEngine(
            dispatcher = { _, reqId ->
                seenIds.add(reqId)
                if (seenIds.size == 1) AcquireResult.NetworkFailure(ConnectException("transient"))
                else AcquireResult.Success(account)
            },
        )
        val session = engine.newSession()

        val result = session.acquireReady(requesterUid = 31660292L, requestId = "idem-1")

        assertTrue(result is AcquireResult.Success)
        assertEquals(listOf("idem-1", "idem-1"), seenIds)
        assertEquals(account, session.currentAccount)
        assertEquals(BorrowedSessionState.Active(account), session.stateFlow.value)
    }

    @Test
    fun `not premium account on fetch is durably reported back to outbox`() = runTest {
        val store = FakeAccountReportStore()
        val observer = RecordingSessionObserver()
        val lapsedPayload = TestPayload(uid = 102L, accessToken = "a", refreshToken = "r", isPremium = false)
        val engine = buildEngine(
            reportStore = store,
            observer = observer,
            dispatcher = { _, _ -> AcquireResult.NotPremium(uid = 102L, payload = lapsedPayload) },
        )
        val session = engine.newSession()

        val result = session.acquireReady(requesterUid = 1L, requestId = "r1")

        assertTrue(result is AcquireResult.NotPremium)
        assertEquals(listOf(102L to lapsedPayload), store.persistedOnline)
        assertEquals(listOf(102L), store.attemptedOnline)
        assertEquals(BorrowedSessionState.Idle, session.state)
        assertTrue(observer.events.any { it is SessionEvent.NotPremiumDetected && it.fromDispatch })
    }

    @Test
    fun `rate limited dispatch notifies observer with RateLimitInfo helpers`() = runTest {
        val observer = RecordingSessionObserver()
        val limitInfo = RateLimitInfo.create {
            retryAfterSeconds = 3600L
            scope = "uid_5h"
            resetsAtMs = 2_000_000L
            serverTimeMs = 1_000_000L
        }
        val engine = buildEngine(
            observer = observer,
            dispatcher = { _, _ -> AcquireResult.RateLimited(limitInfo) },
        )

        val result = engine.newSession().acquireReady(requesterUid = 1L, requestId = "r1")

        assertTrue(result is AcquireResult.RateLimited)
        assertEquals("rate_limited", result.reasonLabel)
        val completed = observer.events.filterIsInstance<SessionEvent.FetchCompleted>().single()
        val outcome = completed.outcome as FetchEventOutcome.RateLimited
        assertTrue(outcome.info.matchesScope(setOf("uid_5h", "uid_weekly")))
        assertFalse(outcome.info.matchesScope(setOf("global")))
        assertEquals(1_000_000L, outcome.info.remainingResetMs())
        assertEquals(limitInfo, outcome.info)
    }

    @Test
    fun `server expired account is renewed, persisted, reported, and installed as Active`() = runTest {
        val clock = FakeSessionClock(initialWallMs = 2_000_000L)
        val store = FakeAccountReportStore()
        val stale = testBorrowedAccount(
            uid = 102L,
            accessToken = "old-access",
            refreshToken = "old-refresh",
            updatedAtMs = 1_000L,
            expiresAtMs = 2_000L,
            expired = true,
            isPremium = true,
        )
        val engine = buildEngine(
            clock = clock,
            reportStore = store,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { uid, rt ->
                assertEquals(102L, uid)
                assertEquals("old-refresh", rt)
                TokenRefreshOutcome.Success(
                    testRefreshedTokens(
                        accessToken = "new-access",
                        refreshToken = "new-refresh",
                        expiresInSeconds = 3600,
                        reportedUid = 102L,
                        // Omitted membership must preserve stored isPremium=true!
                        reportedIsPremium = null,
                    ),
                )
            },
        )
        val session = engine.newSession()

        val result = session.acquireReady(requesterUid = 1L, requestId = "r1")

        assertTrue(result is AcquireResult.Success)
        val active = (result as AcquireResult.Success).account
        assertEquals("new-access", active.accessToken)
        assertEquals("new-refresh", active.refreshToken)
        assertFalse(active.expired)
        assertEquals(true, active.isPremium)
        assertEquals(2_000_000L, active.updatedAtMs)
        assertEquals(2_000_000L + Nana7miSessionConfig.DEFAULT_TOKEN_VALID_DURATION_MS, active.expiresAtMs)
        assertEquals("owner-102", active.payload.displayName)
        assertEquals(true, active.payload.isPremium)
        assertEquals(1, store.persistedOnline.size)
        assertEquals("new-refresh", store.persistedOnline.single().second.refreshToken)
        assertEquals(listOf(102L), store.attemptedOnline)
        assertEquals(BorrowedSessionState.Active(active), session.state)
    }

    @Test
    fun `renewal refuses to return Success when durable persistence fails`() = runTest {
        val store = FakeAccountReportStore(persistOnlineResult = false)
        val stale = testBorrowedAccount(uid = 102L, expired = true)
        val engine = buildEngine(
            reportStore = store,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { _, _ ->
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("new-at", "new-rt", reportedUid = 102L, reportedIsPremium = true),
                )
            },
        )
        val session = engine.newSession()

        val result = session.acquireReady(requesterUid = 1L, requestId = "r1")

        assertTrue(result is AcquireResult.InvalidResponse)
        assertEquals(0, store.attemptedOnline.size)
        assertNull(session.currentAccount)
    }

    @Test
    fun `invalid refresh token queues quarantine report and returns InvalidResponse`() = runTest {
        val store = FakeAccountReportStore()
        val stale = testBorrowedAccount(uid = 102L, refreshToken = "revoked-rt", expired = true)
        val engine = buildEngine(
            reportStore = store,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { _, _ ->
                TokenRefreshOutcome.InvalidRefreshToken(RuntimeException("Invalid refresh token"))
            },
        )

        val result = engine.newSession().acquireReady(requesterUid = 1L, requestId = "r1")

        assertTrue(result is AcquireResult.InvalidResponse)
        assertEquals(listOf(102L to "revoked-rt"), store.persistedInvalid)
        assertTrue(store.persistedOnline.isEmpty())
    }

    @Test
    fun `lapsed premium discovered during renewal persists rotated tokens and returns NotPremium`() = runTest {
        val store = FakeAccountReportStore()
        val observer = RecordingSessionObserver()
        val stale = testBorrowedAccount(uid = 102L, expired = true, isPremium = true)
        val engine = buildEngine(
            reportStore = store,
            observer = observer,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { _, _ ->
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("new-at", "new-rt", reportedUid = 102L, reportedIsPremium = false),
                )
            },
        )
        val session = engine.newSession()

        val result = session.acquireReady(requesterUid = 1L, requestId = "r1")

        assertTrue(result is AcquireResult.NotPremium)
        val notPremium = result as AcquireResult.NotPremium
        assertEquals(102L, notPremium.uid)
        assertEquals("new-rt", notPremium.payload.refreshToken)
        assertEquals(false, notPremium.payload.isPremium)
        assertEquals(1, store.persistedOnline.size)
        assertEquals(listOf(102L), store.attemptedOnline)
        assertNull(session.currentAccount)
        val detected = observer.events.filterIsInstance<SessionEvent.NotPremiumDetected>().single()
        assertEquals(RenewalReason.SERVER_EXPIRED, detected.renewalReason)
    }

    @Test
    fun `mismatched uid in refresh response does not demote account`() = runTest {
        val store = FakeAccountReportStore()
        val stale = testBorrowedAccount(uid = 102L, expired = true, isPremium = true)
        val engine = buildEngine(
            reportStore = store,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { _, _ ->
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("new-at", "new-rt", reportedUid = 999L, reportedIsPremium = false),
                )
            },
        )

        val result = engine.newSession().acquireReady(requesterUid = 1L, requestId = "r1")

        assertTrue(result is AcquireResult.Success)
        assertEquals(true, (result as AcquireResult.Success).account.isPremium)
    }

    @Test
    fun `local expiry triggers proactive renewal before sending upstream request`() = runTest {
        val clock = FakeSessionClock(initialWallMs = 5_000_000L)
        val store = FakeAccountReportStore()
        val stale = testBorrowedAccount(
            uid = 102L,
            accessToken = "stale-at",
            refreshToken = "stale-rt",
            updatedAtMs = 1_000_000L,
            expiresAtMs = 5_000_000L, // <= now
            expired = false,
        )
        val engine = buildEngine(
            clock = clock,
            reportStore = store,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { _, _ ->
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("fresh-at", "fresh-rt", reportedUid = 102L, reportedIsPremium = true),
                )
            },
        )
        val session = engine.newSession()
        val seenHeaders = mutableListOf<String>()

        val response = engine.serialCoordinator.run("test") { lease ->
            session.executeWithRefresh(
                initial = stale,
                lease = lease,
                stage = "search",
            ) { account ->
                seenHeaders.add(account.authorizationHeader())
                "page"
            }
        }

        assertEquals("page", response)
        assertEquals(listOf("Bearer fresh-at"), seenHeaders)
        assertEquals("fresh-at", session.currentAccount?.accessToken)
    }

    @Test
    fun `reactive auth rejection refreshes and replays exactly once`() = runTest {
        val clock = FakeSessionClock(initialWallMs = 1_000_000L)
        val initial = testBorrowedAccount(
            uid = 102L,
            accessToken = "old-at",
            refreshToken = "old-rt",
            updatedAtMs = 1_000_000L,
            expiresAtMs = 9_000_000L,
        )
        var refreshCalls = 0
        val engine = buildEngine(
            clock = clock,
            dispatcher = { _, _ -> AcquireResult.Success(initial) },
            refresher = { _, _ ->
                refreshCalls++
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("new-at", "new-rt", reportedUid = 102L, reportedIsPremium = true),
                )
            },
        )
        val session = engine.newSession()
        val seenHeaders = mutableListOf<String>()

        val response = engine.serialCoordinator.run("test") { lease ->
            session.executeWithRefresh(initial = initial, lease = lease, stage = "search") { acc ->
                seenHeaders.add(acc.authorizationHeader())
                if (seenHeaders.size == 1) throw OAuthExpiredException()
                "ok"
            }
        }

        assertEquals("ok", response)
        assertEquals(1, refreshCalls)
        assertEquals(listOf("Bearer old-at", "Bearer new-at"), seenHeaders)
        assertEquals("new-at", session.currentAccount?.accessToken)
    }

    @Test
    fun `second auth rejection on same page does not loop refreshing`() = runTest {
        val clock = FakeSessionClock(initialWallMs = 1_000_000L)
        val initial = testBorrowedAccount(
            uid = 102L,
            updatedAtMs = 1_000_000L,
            expiresAtMs = 9_000_000L,
        )
        var refreshCalls = 0
        val engine = buildEngine(
            clock = clock,
            dispatcher = { _, _ -> AcquireResult.Success(initial) },
            refresher = { _, _ ->
                refreshCalls++
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("new-at", "new-rt", reportedUid = 102L, reportedIsPremium = true),
                )
            },
        )
        val session = engine.newSession()

        val error = runCatching {
            engine.serialCoordinator.run("test") { lease ->
                session.executeWithRefresh(initial = initial, lease = lease, stage = "search") {
                    throw OAuthExpiredException()
                }
            }
        }.exceptionOrNull()

        assertTrue(error is OAuthExpiredException)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `renewal failure transitions to strictly terminal Lost state that never resurrects`() = runTest {
        val clock = FakeSessionClock(initialWallMs = 10_000L)
        val stale = testBorrowedAccount(
            uid = 42L,
            accessToken = "expired-access",
            refreshToken = null,
            updatedAtMs = 0L,
            expiresAtMs = 0L,
            expired = false,
        )
        var dispatchCalls = 0
        val engine = buildEngine(
            clock = clock,
            dispatcher = { _, _ ->
                dispatchCalls++
                AcquireResult.Success(stale)
            },
        )
        val session = engine.newSession()
        val acquired = session.acquireReady(requesterUid = 1L, requestId = "r1")
        assertTrue(acquired is AcquireResult.Success)
        assertTrue(session.state is BorrowedSessionState.Active)

        val error = runCatching {
            engine.serialCoordinator.run("renewal_failure") { lease ->
                session.executeWithRefresh(initial = stale, lease = lease, stage = "test") {
                    "unexpected"
                }
            }
        }.exceptionOrNull()

        assertTrue(error is BorrowedAccountUnavailableException)
        assertTrue(error!!.isBorrowedAccountUnavailable())
        assertNull(session.currentAccount)
        assertTrue(session.isLost)
        assertEquals(BorrowedSessionState.Lost, session.state)

        // Lost is strictly terminal: cannot dispatch again or markCursorFromCache!
        val afterLost = session.acquireReady(requesterUid = 1L, requestId = "r2")
        assertTrue(afterLost is AcquireResult.InvalidResponse)
        assertEquals(1, dispatchCalls)
        assertTrue(runCatching { session.markCursorFromCache("id", true) }.isFailure)
    }

    @Test
    fun `cached cursor enforces FSM idle precondition and retries once on 409 conflict`() = runTest {
        val seenIds = mutableListOf<String?>()
        val account = testBorrowedAccount(uid = 200L)
        val engine = buildEngine(
            requestIds = listOf("fresh-id-2"),
            dispatcher = { _, reqId ->
                seenIds.add(reqId)
                if (seenIds.size == 1) AcquireResult.HttpFailure(409)
                else AcquireResult.Success(account)
            },
        )
        val session = engine.newSession()
        session.markCursorFromCache(paidRequestId = "paid-first-1", requestIdRequired = true)
        assertTrue(session.isCursorFromCache)
        // Calling markCursorFromCache twice violates Idle -> CachedCursor FSM guard.
        assertTrue(runCatching { session.markCursorFromCache("other", true) }.isFailure)

        val result = session.acquireForCachedCursor(requesterUid = 1L)

        assertTrue(result is AcquireResult.Success)
        assertEquals(listOf("paid-first-1", "fresh-id-2"), seenIds)
        assertEquals(account, session.currentAccount)
    }

    @Test
    fun `cached cursor does not retry non-409 failures`() = runTest {
        val seenIds = mutableListOf<String?>()
        val engine = buildEngine(
            dispatcher = { _, reqId ->
                seenIds.add(reqId)
                AcquireResult.HttpFailure(500)
            },
        )
        val session = engine.newSession()
        session.markCursorFromCache(paidRequestId = "paid-first-1", requestIdRequired = true)

        val result = session.acquireForCachedCursor(requesterUid = 1L)

        assertEquals(AcquireResult.HttpFailure(500), result)
        assertEquals(listOf("paid-first-1"), seenIds)
    }

    @Test
    fun `markCursorFromCache rejects missing paid id when protocol requires it`() {
        val engine = buildEngine(dispatcher = { _, _ -> AcquireResult.NoAccount })
        val session = engine.newSession()

        val error = runCatching {
            session.markCursorFromCache(paidRequestId = null, requestIdRequired = true)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `cancellation during blocking token refresh still durably persists rotated token`() = runBlocking {
        val store = FakeAccountReportStore()
        val enteredRefresh = CountDownLatch(1)
        val allowRefreshReturn = CountDownLatch(1)
        val stale = testBorrowedAccount(uid = 102L, expired = true)
        val engine = buildEngine(
            reportStore = store,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { _, _ ->
                enteredRefresh.countDown()
                allowRefreshReturn.await(2, TimeUnit.SECONDS)
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("rotated-at", "rotated-rt", reportedUid = 102L, reportedIsPremium = true),
                )
            },
        )
        val session = engine.newSession()

        val job = launch(Dispatchers.IO) {
            engine.serialCoordinator.run("cancel_mid_refresh") { lease ->
                lease.guarded {
                    session.acquireReady(requesterUid = 1L, requestId = "r1")
                }
            }
        }

        assertTrue(enteredRefresh.await(1, TimeUnit.SECONDS))
        job.cancel()
        allowRefreshReturn.countDown()
        job.join()

        assertEquals(1, store.persistedOnline.size)
        assertEquals("rotated-rt", store.persistedOnline.single().second.refreshToken)
    }

    @Test
    fun `isPaidRequestIdRejected matches only HTTP 409`() {
        assertTrue(AcquireResult.HttpFailure(409).isPaidRequestIdRejected())
        assertFalse(AcquireResult.HttpFailure(500).isPaidRequestIdRejected())
        assertFalse(AcquireResult.NoAccount.isPaidRequestIdRejected())
        assertFalse(AcquireResult.RateLimited(RateLimitInfo.create {}).isPaidRequestIdRejected())
    }

    @Test
    fun `credential models redact raw tokens in toString and reject blank refreshed tokens`() {
        val account = testBorrowedAccount(
            uid = 102L,
            accessToken = "super-secret-access",
            refreshToken = "super-secret-refresh",
        )
        val tokens = testRefreshedTokens(
            accessToken = "rotated-secret-access",
            refreshToken = "rotated-secret-refresh",
        )

        assertFalse(account.toString().contains("super-secret-access"))
        assertFalse(account.toString().contains("super-secret-refresh"))
        assertFalse(tokens.toString().contains("rotated-secret-access"))
        assertFalse(tokens.toString().contains("rotated-secret-refresh"))
        assertTrue(runCatching { testRefreshedTokens(" ", "rt") }.isFailure)
        assertTrue(runCatching { testRefreshedTokens("at", "") }.isFailure)

        // newBuilder round-trip preserves equality
        assertEquals(account, account.newBuilder(account.payload).build())
        assertEquals(tokens, tokens.newBuilder().build())
    }

    @Test
    fun `throwing reportStore on delivery or persistence is isolated and preserves session invariants`() = runTest {
        val observer = RecordingSessionObserver()
        val stale = testBorrowedAccount(uid = 102L, expired = true)

        // 1) attemptOnline throws IOException after durable persistOnline succeeds: renewal still succeeds
        val deliveringStore = InMemoryAccountReportStore<TestPayload> { _, _ ->
            throw IOException("backend unreachable")
        }
        val deliverEngine = buildEngine(
            reportStore = deliveringStore,
            observer = observer,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { _, _ ->
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("new-at", "new-rt", reportedUid = 102L, reportedIsPremium = true),
                )
            },
        )
        val deliverResult = deliverEngine.newSession().acquireReady(requesterUid = 1L, requestId = "r1")
        assertTrue(deliverResult is AcquireResult.Success)
        assertEquals("new-rt", deliveringStore.latestOnlinePayload(102L)?.refreshToken)
        assertTrue(
            observer.events.any {
                it is SessionEvent.OnlineReportCompleted && it.outcome == ReportDeliveryOutcome.FAILED
            },
        )

        // 2) persistOnline throws IOException: renewal refuses Success and returns InvalidResponse
        val brokenPersistStore = object : AccountReportStore<TestPayload> {
            override suspend fun persistOnline(uid: Long, payload: TestPayload): Boolean =
                throw IOException("disk full")

            override suspend fun attemptOnline(uid: Long): ReportDeliveryOutcome =
                ReportDeliveryOutcome.FAILED

            override suspend fun persistInvalidRefreshToken(uid: Long, refreshToken: String): Boolean =
                throw IOException("disk full")
        }
        val persistEngine = buildEngine(
            reportStore = brokenPersistStore,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
            refresher = { _, _ ->
                TokenRefreshOutcome.Success(
                    testRefreshedTokens("new-at", "new-rt", reportedUid = 102L, reportedIsPremium = true),
                )
            },
        )
        val persistResult = persistEngine.newSession().acquireReady(requesterUid = 1L, requestId = "r2")
        assertTrue(persistResult is AcquireResult.InvalidResponse)
        assertTrue((persistResult as AcquireResult.InvalidResponse).cause is IOException)
    }

    @Test
    fun `Lost session returns InvalidResponse from acquireForCachedCursor and rejects executeWithRefresh`() = runTest {
        val clock = FakeSessionClock(initialWallMs = 10_000L)
        val stale = testBorrowedAccount(uid = 42L, refreshToken = null, updatedAtMs = 0L, expiresAtMs = 0L)
        val engine = buildEngine(
            clock = clock,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
        )
        val session = engine.newSession()
        session.acquireReady(requesterUid = 1L, requestId = "r1")
        runCatching {
            engine.serialCoordinator.run("lose") { lease ->
                session.executeWithRefresh(initial = stale, lease = lease, stage = "s") { "x" }
            }
        }
        assertTrue(session.isLost)

        val handoff = session.acquireForCachedCursor(requesterUid = 1L)
        assertTrue(handoff is AcquireResult.InvalidResponse)

        val execErr = runCatching {
            engine.serialCoordinator.run("after_lost") { lease ->
                session.executeWithRefresh(initial = stale, lease = lease, stage = "s") { "never" }
            }
        }.exceptionOrNull()
        assertTrue(execErr is BorrowedAccountUnavailableException)
    }
}
