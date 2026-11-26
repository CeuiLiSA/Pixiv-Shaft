package ceui.pixiv.actions

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.lisa.viewmodel.AppLevelState
import ceui.pixiv.actionqueue.ActionHandler
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.ActionQueue
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.ActionStore
import ceui.pixiv.actionqueue.Clock
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.actionqueue.QueuePolicy
import ceui.pixiv.actionqueue.StoredAction
import ceui.pixiv.analytics.Analytics
import ceui.pixiv.db.discovery.ProfileManager
import ceui.pixiv.db.mirror.BookmarkMirrorService
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.events.EventReporter
import ceui.pixiv.services.ServicesProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

/**
 * 校验批量清理失效作品 / 失效关注直接复用既有 [PixivActionQueue]，并强制走 5 秒冷却间隔
 * （[PixivActions.BULK_CLEANUP_GAP_MS]），不会一股脑连发请求。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PixivActionsCleanupTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: InMemoryActionStore
    private lateinit var actionQueue: ActionQueue
    private lateinit var pixivActionQueue: PixivActionQueue
    private val executedAtMs = mutableListOf<Pair<String, Long>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Shaft.sGson = Gson()
        Shaft.sSettings = Settings()
        executedAtMs.clear()
    }

    @After
    fun tearDown() {
        if (::actionQueue.isInitialized) actionQueue.stop()
        Dispatchers.resetMain()
    }

    private fun installTestHost(testScope: TestScope) {
        val app = RuntimeEnvironment.getApplication()
        store = InMemoryActionStore()
        val fakeClock = Clock { testScope.testScheduler.currentTime }
        val recorder = ActionHandler { action ->
            executedAtMs += action.dedupeKey to fakeClock.nowMs()
            ActionOutcome.Success
        }
        actionQueue = ActionQueue(
            store = store,
            handlers = mapOf(
                PixivActionTypes.ILLUST_BOOKMARK to recorder,
                PixivActionTypes.NOVEL_BOOKMARK to recorder,
                PixivActionTypes.USER_FOLLOW to recorder,
            ),
            // 生产主队列默认最小间隔 2 秒；批量清理必须靠 ActionRequest.gapMs 抬到 5 秒。
            policy = QueuePolicy(minGapMs = 2_000L, idlePollMs = 60_000L),
            clock = fakeClock,
            scope = testScope.backgroundScope,
        )
        val appLevelState = AppLevelState()
        val analytics = Analytics(app)
        val profileManager = ProfileManager(app)
        val eventReporter = EventReporter()
        val mirror = BookmarkMirrorService(app)
        pixivActionQueue = PixivActionQueue(app, profileManager, eventReporter, mirror, analytics)
        ReflectionHelpers.setField(pixivActionQueue, "queue", actionQueue)

        val services = Proxy.newProxyInstance(
            ServicesProvider::class.java.classLoader,
            arrayOf(ServicesProvider::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPixivActionQueue" -> pixivActionQueue
                "getAnalytics" -> analytics
                "getAppLevelState" -> appLevelState
                "getBookmarkMirror" -> mirror
                else -> error("unexpected service call: ${method.name}")
            }
        } as ServicesProvider
        val wrapped = object : ContextWrapper(app), ServicesProvider by services {
            override fun getApplicationContext(): Context = this
        }
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", wrapped)
    }

    @Test
    fun `bulk invalid illust cleanup enqueues with 5s gap and executes strictly one every 5 seconds`() =
        runTest(dispatcher) {
            installTestHost(this)
            actionQueue.start()
            runCurrent()

            val count = PixivActions.cleanupInvalidItems(
                MirrorContentType.ILLUST,
                listOf(101L, 102L, 101L, 0L, 103L),
            )
            assertEquals(3, count)
            runCurrent()

            // 入队载荷校验：取消收藏 + skipMetaFetch + 5 秒间隔
            assertEquals(3, store.enqueuedRequests.size)
            store.enqueuedRequests.forEach { req ->
                assertEquals(PixivActionTypes.ILLUST_BOOKMARK, req.type)
                assertEquals(5_000L, req.gapMs)
                val payload = Gson().fromJson(req.payload, BookmarkPayload::class.java)
                assertFalse(payload.bookmark)
                assertTrue(payload.skipMetaFetch)
            }

            // 第 1 条立刻执行（0ms），第 2 条必须等满 5000ms，第 3 条等满 10000ms
            assertEquals(listOf("illust_bookmark:101" to 0L), executedAtMs)

            advanceTimeBy(4_999L)
            runCurrent()
            assertEquals("不足 5 秒不得发第 2 条", 1, executedAtMs.size)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(
                listOf("illust_bookmark:101" to 0L, "illust_bookmark:102" to 5_000L),
                executedAtMs,
            )

            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(
                listOf(
                    "illust_bookmark:101" to 0L,
                    "illust_bookmark:102" to 5_000L,
                    "illust_bookmark:103" to 10_000L,
                ),
                executedAtMs,
            )
            actionQueue.stop()
        }

    @Test
    fun `bulk invalid following cleanup enqueues unfollows with 5s cooldown`() = runTest(dispatcher) {
        installTestHost(this)
        actionQueue.start()
        runCurrent()

        val count = PixivActions.cleanupInvalidItems(MirrorContentType.USER, listOf(201L, 202L))
        assertEquals(2, count)
        runCurrent()

        assertEquals(2, store.enqueuedRequests.size)
        store.enqueuedRequests.forEach { req ->
            assertEquals(PixivActionTypes.USER_FOLLOW, req.type)
            assertEquals(5_000L, req.gapMs)
            val payload = Gson().fromJson(req.payload, FollowPayload::class.java)
            assertFalse(payload.follow)
            assertTrue(payload.skipMetaFetch)
        }

        assertEquals(listOf("user_follow:201" to 0L), executedAtMs)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(
            listOf("user_follow:201" to 0L, "user_follow:202" to 5_000L),
            executedAtMs,
        )
        assertEquals(40, PixivActions.estimatedQueueSeconds(8))
        actionQueue.stop()
    }

    private class InMemoryActionStore : ActionStore {
        private data class Row(
            val id: Long,
            val request: ActionRequest,
            val owner: String,
            var status: String = "PENDING",
            var attempt: Int = 0,
            var notBeforeMs: Long = 0L,
        )

        private var nextId = 1L
        private val rows = mutableListOf<Row>()
        private var cooldownUntilMs = 0L
        val enqueuedRequests = mutableListOf<ActionRequest>()

        override suspend fun enqueue(request: ActionRequest, nowMs: Long, owner: String): Long {
            enqueuedRequests += request
            if (request.coalesce) {
                rows.removeAll { it.owner == owner && it.status == "PENDING" && it.request.dedupeKey == request.dedupeKey }
            }
            val id = nextId++
            rows += Row(id = id, request = request, owner = owner)
            return id
        }

        override suspend fun nextRunnable(nowMs: Long, owner: String): StoredAction? =
            rows.firstOrNull { it.owner == owner && it.status == "PENDING" && it.notBeforeMs <= nowMs }?.let {
                StoredAction(
                    action = PendingAction(
                        id = it.id,
                        type = it.request.type,
                        dedupeKey = it.request.dedupeKey,
                        payload = it.request.payload,
                        attempt = it.attempt,
                        owner = it.owner,
                    ),
                    gapMs = it.request.gapMs,
                )
            }

        override suspend fun earliestNotBeforeMs(owner: String): Long? =
            rows.filter { it.owner == owner && it.status == "PENDING" }.minOfOrNull { it.notBeforeMs }

        override suspend fun markRunning(id: Long) {
            rows.firstOrNull { it.id == id }?.status = "RUNNING"
        }

        override suspend fun releaseToPending(id: Long) {
            rows.firstOrNull { it.id == id }?.status = "PENDING"
        }

        override suspend fun rescheduleForRetry(id: Long, notBeforeMs: Long, incrementAttempt: Boolean) {
            rows.firstOrNull { it.id == id }?.let {
                it.status = "PENDING"
                it.notBeforeMs = notBeforeMs
                if (incrementAttempt) it.attempt++
            }
        }

        override suspend fun markFailed(id: Long, reason: String) {
            rows.firstOrNull { it.id == id }?.status = "FAILED"
        }

        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun resurrectRunning(): Int {
            var count = 0
            rows.forEach {
                if (it.status == "RUNNING") {
                    it.status = "PENDING"
                    count++
                }
            }
            return count
        }

        override suspend fun hasPending(dedupeKey: String, owner: String, excludeId: Long): Boolean =
            rows.any { it.owner == owner && it.status == "PENDING" && it.request.dedupeKey == dedupeKey && it.id != excludeId }

        override suspend fun retryAllFailed(nowMs: Long, owner: String): Int = 0
        override suspend fun clearFailed(owner: String): Int = 0
        override suspend fun clearPending(owner: String): Int {
            val before = rows.size
            rows.removeAll { it.owner == owner && it.status == "PENDING" }
            return before - rows.size
        }

        override suspend fun pruneFailed(owner: String, keepLatest: Int): Int = 0
        override suspend fun pruneToMaxRows(owner: String, maxRows: Int): Int = 0
        override suspend fun pendingCount(owner: String): Int =
            rows.count { it.owner == owner && it.status == "PENDING" }

        override suspend fun failedCount(owner: String): Int =
            rows.count { it.owner == owner && it.status == "FAILED" }

        override suspend fun loadCooldownUntilMs(): Long = cooldownUntilMs
        override suspend fun saveCooldownUntilMs(untilMs: Long) {
            cooldownUntilMs = untilMs
        }
    }
}
