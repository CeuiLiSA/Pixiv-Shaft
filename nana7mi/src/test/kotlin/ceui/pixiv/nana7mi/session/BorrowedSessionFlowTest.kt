package ceui.pixiv.nana7mi.session

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BorrowedSessionFlowTest {

    private class OAuthExpiredException : RuntimeException("oauth expired")

    private fun buildEngine(
        clock: FakeSessionClock = FakeSessionClock(),
        reportStore: AccountReportStore<TestPayload> = FakeAccountReportStore(),
        observer: SessionObserver = SessionObserver.NO_OP,
        requestIds: List<String> = listOf("req-first", "req-next", "req-fresh"),
        dispatcher: AccountDispatcher<TestPayload>,
        refresher: AccountTokenRefresher = AccountTokenRefresher { _, _ ->
            TokenRefreshOutcome.Success(
                testRefreshedTokens("refreshed-at", "refreshed-rt", reportedUid = 102L, reportedIsPremium = true),
            )
        },
    ): Nana7miSessionEngine<TestPayload> {
        val idx = AtomicInteger(0)
        return Nana7miSessionEngine.create {
            this.dispatcher = dispatcher
            this.tokenRefresher = refresher
            this.payloadUpdater = TestPayloadUpdater
            this.reportStore = reportStore
            this.authFailureClassifier = AuthFailureClassifier { it is OAuthExpiredException }
            this.retryPolicy = NetworkRetryPolicy.create { retryDelayMs = 0L }
            this.requestIdGenerator = RequestIdGenerator {
                requestIds.getOrElse(idx.getAndIncrement()) { "req-extra" }
            }
            this.clock = clock
            this.observer = observer
        }
    }

    @Test
    fun `first page cache hit sets CachedCursor, invokes hooks in order, and never dispatches`() = runTest {
        var dispatchCalls = 0
        val trace = mutableListOf<String>()
        val engine = buildEngine(
            dispatcher = { _, _ ->
                dispatchCalls++
                AcquireResult.NoAccount
            },
        )
        val session = engine.newSession()
        val pageCache = object : BorrowedPageCache<String> {
            override suspend fun lookup(requestId: String?): String {
                trace.add("cache_lookup:$requestId")
                return "raw-cached"
            }

            override fun store(page: String) {
                trace.add("unexpected_store")
            }
        }
        val flowHooks = object : FirstPageHooks {
            override fun onCacheHit(requestId: String?) {
                trace.add("on_cache_hit:$requestId")
            }

            override suspend fun <T : Any> aroundFlow(block: suspend () -> T): T {
                trace.add("flow_start")
                return block().also { trace.add("flow_end") }
            }

            override suspend fun <T : Any> aroundCacheHit(requestId: String?, block: suspend () -> T): T {
                trace.add("around_cache_hit:$requestId")
                return block()
            }
        }

        val page = session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            serialStage = "illust_first"
            requestStage = "official_search"
            cache = pageCache
            hooks = flowHooks
            postProcess { "$it+viewer" }
            fallback { error("unexpected fallback") }
            request { error("unexpected live request") }
        }

        assertEquals("raw-cached+viewer", page)
        assertEquals(0, dispatchCalls)
        assertEquals(
            BorrowedSessionState.CachedCursor(paidRequestId = "req-first", requestIdRequired = true),
            session.state,
        )
        assertEquals(
            listOf(
                "cache_lookup:req-first",
                "on_cache_hit:req-first",
                "flow_start",
                "around_cache_hit:req-first",
                "flow_end",
            ),
            trace,
        )
    }

    @Test
    fun `first page cache miss borrows, stores raw page before postProcess, and reuses single requestId`() = runTest {
        val account = testBorrowedAccount(uid = 102L)
        val storedPages = mutableListOf<String>()
        val seenIds = mutableListOf<String?>()
        val engine = buildEngine(
            dispatcher = { _, reqId ->
                seenIds.add("dispatch:$reqId")
                AcquireResult.Success(account)
            },
        )
        val session = engine.newSession()
        val pageCache = object : BorrowedPageCache<String> {
            override suspend fun lookup(requestId: String?): String? {
                seenIds.add("lookup:$requestId")
                return null
            }

            override fun store(page: String) {
                storedPages.add(page)
            }
        }
        val flowHooks = object : FirstPageHooks {
            override suspend fun <T : Any> aroundBorrowedRequest(
                accountUid: Long,
                requestId: String?,
                block: suspend () -> T,
            ): T {
                seenIds.add("hook:$accountUid:$requestId")
                return block()
            }
        }

        val page = session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            serialStage = "illust_first"
            requestStage = "official_search"
            cache = pageCache
            hooks = flowHooks
            postProcess { "$it+viewer" }
            fallback { error("unexpected fallback") }
            request { acc -> "raw-from-${acc.uid}" }
        }

        assertEquals("raw-from-102+viewer", page)
        assertEquals(listOf("raw-from-102"), storedPages)
        assertEquals(
            listOf("lookup:req-first", "dispatch:req-first", "hook:102:req-first"),
            seenIds,
        )
        assertEquals(BorrowedSessionState.Active(account), session.state)
    }

    @Test
    fun `first page borrow failure falls back with typed FallbackReason and keeps session Idle`() = runTest {
        val trace = mutableListOf<String>()
        val engine = buildEngine(dispatcher = { _, _ -> AcquireResult.NoAccount })
        val session = engine.newSession()
        val flowHooks = object : FirstPageHooks {
            override fun onFallback(reason: FallbackReason) {
                assertTrue(reason is FallbackReason.AcquireFailed)
                trace.add("on_fallback:${reason.code}")
            }

            override suspend fun <T : Any> aroundFallbackRequest(
                reason: FallbackReason,
                block: suspend () -> T,
            ): T {
                trace.add("around_fallback:${reason.code}")
                return block()
            }
        }

        val firstPage = session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            hooks = flowHooks
            fallback { reason -> "preview:${reason.code}" }
            request { error("unexpected request") }
        }

        assertEquals("preview:no_account", firstPage)
        assertEquals(BorrowedSessionState.Idle, session.state)
        assertEquals(listOf("on_fallback:no_account", "around_fallback:no_account"), trace)

        val nextPage = session.executeNextPage {
            requesterUid = 1L
            idempotencyEnabled = true
            directRequest { "direct-next" }
            endPagination { error("should not end direct pagination") }
            request { error("should not use borrowed request") }
        }
        assertEquals("direct-next", nextPage)
    }

    @Test
    fun `first page renewal failure falls back with AccountUnavailable`() = runTest {
        val clock = FakeSessionClock(initialWallMs = 10_000L)
        val stale = testBorrowedAccount(uid = 102L, refreshToken = null, updatedAtMs = 0L, expiresAtMs = 0L)
        val engine = buildEngine(
            clock = clock,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
        )
        val session = engine.newSession()
        var capturedReason: FallbackReason? = null

        val page = session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            fallback { reason ->
                capturedReason = reason
                "fallback:${reason.code}"
            }
            request { "unexpected" }
        }

        assertTrue(capturedReason is FallbackReason.AccountUnavailable)
        assertEquals("fallback:borrowed_refresh_failed", page)
        assertEquals(BorrowedSessionState.Lost, session.state)
    }

    @Test
    fun `pagination on CachedCursor hands off paid first requestId and retries 409 conflict`() = runTest {
        val seenDispatchIds = CopyOnWriteArrayList<String?>()
        val account = testBorrowedAccount(uid = 300L)
        val engine = buildEngine(
            requestIds = listOf("paid-first", "next-page-req", "fresh-after-409"),
            dispatcher = { _, reqId ->
                seenDispatchIds.add(reqId)
                if (seenDispatchIds.size == 1) AcquireResult.HttpFailure(409)
                else AcquireResult.Success(account)
            },
        )
        val session = engine.newSession()

        val firstCache = object : BorrowedPageCache<String> {
            override suspend fun lookup(requestId: String?): String = "cached-p1"
            override fun store(page: String) {}
        }
        val page1: String = session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            cache = firstCache
            fallback { error("unexpected") }
            request { error("unexpected") }
        }
        assertEquals("cached-p1", page1)
        assertTrue(session.isCursorFromCache)

        val nextStored = mutableListOf<String>()
        var hookEventId: String? = null
        val nextCache = object : BorrowedPageCache<String> {
            override suspend fun lookup(requestId: String?): String? {
                assertEquals("next-page-req", requestId)
                return null
            }

            override fun store(page: String) {
                nextStored.add(page)
            }
        }
        val nextHooks = object : NextPageHooks {
            override suspend fun <T : Any> aroundBorrowedRequest(
                accountUid: Long,
                requestId: String?,
                block: suspend () -> T,
            ): T {
                hookEventId = requestId
                return block()
            }
        }

        val page2 = session.executeNextPage {
            requesterUid = 1L
            idempotencyEnabled = true
            cache = nextCache
            hooks = nextHooks
            directRequest { error("must never fall back to logged-in account") }
            endPagination { error("unexpected end: $it") }
            request { acc -> "p2-from-${acc.uid}" }
        }

        assertEquals("p2-from-300", page2)
        assertEquals(listOf("paid-first", "fresh-after-409"), seenDispatchIds)
        assertEquals("next-page-req", hookEventId)
        assertEquals(listOf("p2-from-300"), nextStored)
        assertEquals(BorrowedSessionState.Active(account), session.state)
    }

    @Test
    fun `pagination on Lost session ends immediately without touching cache, serial, or directRequest`() = runTest {
        val clock = FakeSessionClock(initialWallMs = 10_000L)
        val stale = testBorrowedAccount(uid = 102L, refreshToken = null, updatedAtMs = 0L, expiresAtMs = 0L)
        val engine = buildEngine(
            clock = clock,
            dispatcher = { _, _ -> AcquireResult.Success(stale) },
        )
        val session = engine.newSession()
        session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = false
            fallback { "fallback" }
            request { "unexpected" }
        }
        assertTrue(session.isLost)

        var endedReason: PaginationEndReason? = null
        val page = session.executeNextPage {
            requesterUid = 1L
            idempotencyEnabled = false
            cache = object : BorrowedPageCache<String> {
                override suspend fun lookup(requestId: String?): String = error("must not touch cache")
                override fun store(page: String) {}
            }
            directRequest { error("must not call directRequest") }
            endPagination { reason ->
                endedReason = reason
                "empty-terminal"
            }
            request { error("must not call request") }
        }

        assertEquals("empty-terminal", page)
        assertEquals(PaginationEndReason.ALREADY_LOST, endedReason)
    }

    @Test
    fun `failed handoff on CachedCursor transitions session to Lost and ends pagination`() = runTest {
        val engine = buildEngine(dispatcher = { _, _ -> AcquireResult.NoAccount })
        val session = engine.newSession()
        session.markCursorFromCache(paidRequestId = "paid-1", requestIdRequired = true)

        var firstEnd: PaginationEndReason? = null
        val page2 = session.executeNextPage {
            requesterUid = 1L
            idempotencyEnabled = true
            directRequest { error("must never call directRequest") }
            endPagination { reason ->
                firstEnd = reason
                "ended"
            }
            request { error("unexpected") }
        }

        assertEquals("ended", page2)
        assertEquals(PaginationEndReason.RENEWAL_FAILED, firstEnd)
        assertTrue(session.isLost)

        var secondEnd: PaginationEndReason? = null
        session.executeNextPage {
            requesterUid = 1L
            idempotencyEnabled = true
            directRequest { error("must never call directRequest") }
            endPagination { reason ->
                secondEnd = reason
                "ended-again"
            }
            request { error("unexpected") }
        }
        assertEquals(PaginationEndReason.ALREADY_LOST, secondEnd)
    }

    @Test
    fun `legacy protocol passes null requestId everywhere`() = runTest {
        val account = testBorrowedAccount(uid = 102L)
        var lookupId: String? = "unset"
        var dispatchId: String? = "unset"
        val engine = buildEngine(
            dispatcher = { _, reqId ->
                dispatchId = reqId
                AcquireResult.Success(account)
            },
        )
        val session = engine.newSession()

        session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = false
            cache = object : BorrowedPageCache<String> {
                override suspend fun lookup(requestId: String?): String? {
                    lookupId = requestId
                    return null
                }

                override fun store(page: String) {}
            }
            fallback { "fb" }
            request { "ok" }
        }

        assertNull(lookupId)
        assertNull(dispatchId)
    }

    @Test
    fun `cache lookup and store exceptions are isolated and emit typed CacheOperationFailed events`() = runTest {
        val observer = RecordingSessionObserver()
        val account = testBorrowedAccount(uid = 102L)
        val engine = buildEngine(
            observer = observer,
            dispatcher = { _, _ -> AcquireResult.Success(account) },
        )
        val session = engine.newSession()
        val brokenCache = object : BorrowedPageCache<String> {
            override suspend fun lookup(requestId: String?): String? =
                throw IllegalStateException("cache lookup exploded")

            override fun store(page: String) {
                throw IllegalStateException("cache store exploded")
            }
        }

        val page = session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            cache = brokenCache
            postProcess { "$it+post" }
            fallback { error("cache failure must not prevent live borrow") }
            request { "live" }
        }

        assertEquals("live+post", page)
        val cacheErrors = observer.events.filterIsInstance<SessionEvent.CacheOperationFailed>()
        assertEquals(listOf(CacheOperation.LOOKUP, CacheOperation.STORE), cacheErrors.map { it.operation })
    }

    @Test
    fun `checked IOException from BorrowedPageCache lookup and store is isolated`() = runTest {
        val observer = RecordingSessionObserver()
        val account = testBorrowedAccount(uid = 102L)
        val engine = buildEngine(
            observer = observer,
            dispatcher = { _, _ -> AcquireResult.Success(account) },
        )
        val session = engine.newSession()
        val ioBrokenCache = object : BorrowedPageCache<String> {
            override suspend fun lookup(requestId: String?): String? = throw IOException("cache network down")
            override fun store(page: String) = throw IOException("cache disk full")
        }

        val page = session.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            cache = ioBrokenCache
            fallback { error("cache IOException must not prevent live borrow") }
            request { "live-ok" }
        }

        assertEquals("live-ok", page)
        val cacheErrors = observer.events.filterIsInstance<SessionEvent.CacheOperationFailed>()
        assertEquals(listOf(CacheOperation.LOOKUP, CacheOperation.STORE), cacheErrors.map { it.operation })
    }

    @Test
    fun `closed engine and denied access policy skip cache lookup before falling back`() = runTest {
        var cacheLookups = 0
        val spyCache = object : BorrowedPageCache<String> {
            override suspend fun lookup(requestId: String?): String {
                cacheLookups++
                return "should-not-hit"
            }

            override fun store(page: String) {}
        }

        // 1) Denied access policy skips cache on first page
        val deniedEngine = Nana7miSessionEngine.create<TestPayload> {
            dispatcher = AccountDispatcher { _, _ -> error("must not dispatch") }
            tokenRefresher = AccountTokenRefresher { _, _ -> error("must not refresh") }
            payloadUpdater = TestPayloadUpdater
            reportStore = FakeAccountReportStore()
            accessPolicy = SessionAccessPolicy.denied("disabled_for_lite")
        }
        val deniedPage = deniedEngine.newSession().executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            cache = spyCache
            fallback { reason -> "fb:${reason.code}" }
            request { error("must not run") }
        }
        assertEquals("fb:disabled_for_lite", deniedPage)
        assertEquals(0, cacheLookups)

        // 2) Closed engine skips cache on first page and cached-cursor pagination
        val closableEngine = buildEngine(dispatcher = { _, _ -> error("must not dispatch") })
        val firstSession = closableEngine.newSession()
        val cachedSession = closableEngine.newSession().apply {
            markCursorFromCache(paidRequestId = "paid-1", requestIdRequired = true)
        }
        closableEngine.close()

        val closedFirst = firstSession.executeFirstPage {
            requesterUid = 1L
            idempotencyEnabled = true
            cache = spyCache
            fallback { reason -> "fb:${reason.code}" }
            request { error("must not run") }
        }
        assertEquals("fb:session_engine_closed", closedFirst)
        assertEquals(0, cacheLookups)

        var endedReason: PaginationEndReason? = null
        val closedNext = cachedSession.executeNextPage {
            requesterUid = 1L
            idempotencyEnabled = true
            cache = spyCache
            directRequest { error("must not call direct") }
            endPagination { reason ->
                endedReason = reason
                "ended"
            }
            request { error("must not run") }
        }
        assertEquals("ended", closedNext)
        assertEquals(PaginationEndReason.RENEWAL_FAILED, endedReason)
        assertEquals(0, cacheLookups)
    }

    @Test
    fun `first page fallback releases serial permit before running slow own-account fallback`() = runBlocking {
        val fallbackEntered = CountDownLatch(1)
        val allowFallbackFinish = CountDownLatch(1)
        val account = testBorrowedAccount(uid = 200L)
        val dispatchCount = AtomicInteger(0)
        val engine = buildEngine(
            dispatcher = { _, _ ->
                if (dispatchCount.incrementAndGet() == 1) AcquireResult.NoAccount
                else AcquireResult.Success(account)
            },
        )

        val firstJob = async(Dispatchers.IO) {
            engine.newSession().executeFirstPage {
                requesterUid = 1L
                idempotencyEnabled = true
                fallback {
                    fallbackEntered.countDown()
                    allowFallbackFinish.await(2, TimeUnit.SECONDS)
                    "slow-fallback"
                }
                request { error("unexpected") }
            }
        }

        assertTrue(fallbackEntered.await(1, TimeUnit.SECONDS))

        // While firstJob is blocked inside fallback, secondJob must still acquire the serial permit.
        val secondPage = withTimeout(1_000L) {
            engine.newSession().executeFirstPage {
                requesterUid = 1L
                idempotencyEnabled = true
                fallback { error("unexpected") }
                request { "second-borrowed" }
            }
        }
        assertEquals("second-borrowed", secondPage)

        allowFallbackFinish.countDown()
        assertEquals("slow-fallback", withTimeout(1_000L) { firstJob.await() })
    }
}
