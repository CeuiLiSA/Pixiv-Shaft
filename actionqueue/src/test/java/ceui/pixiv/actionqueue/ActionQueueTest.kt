package ceui.pixiv.actionqueue

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 调度行为的单测。全部跑在虚拟时间上 —— 「撞 429 冷却 30 秒」这种用例真睡 30 秒是没法测的。
 *
 * 注意不要用 `advanceUntilIdle()`：消费循环是无限的，虚拟时间会一直推下去推不完。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionQueueTest {

    private val type = "bookmark"

    private fun request(payload: String, key: String = "illust:1") =
        ActionRequest(type = type, dedupeKey = key, payload = payload)

    /** 关掉抖动，否则退避时长不可预测。 */
    private fun policy(
        minGapMs: Long = 2_000L,
        maxAttempts: Int = 5,
        initialCooldownMs: Long = 30_000L,
    ) = QueuePolicy(
        minGapMs = minGapMs,
        maxAttempts = maxAttempts,
        initialCooldownMs = initialCooldownMs,
        jitterRatio = 0.0,
    )

    private fun TestScope.queue(
        store: ActionStore,
        handler: ActionHandler,
        policy: QueuePolicy = policy(),
        gate: suspend () -> Boolean = { true },
    ) = ActionQueue(
        store = store,
        handlers = mapOf(type to handler),
        policy = policy,
        clock = { testScheduler.currentTime },
        gate = gate,
        scope = backgroundScope,
        random = Random(0),
    )

    @Test
    fun `连续两条动作之间至少隔一个最小间隔`() = runTest {
        val store = FakeActionStore()
        val ranAt = mutableListOf<Long>()
        val q = queue(store, ActionHandler {
            ranAt += testScheduler.currentTime
            ActionOutcome.Success
        })

        q.enqueueAndWait(request("a", key = "illust:1"))
        q.enqueueAndWait(request("b", key = "illust:2"))
        q.start()
        advanceTimeBy(10_000)

        // 首条立即执行（冷启动不平白等一个间隔），第二条隔 2 秒。
        assertEquals(listOf(0L, 2_000L), ranAt)
    }

    @Test
    fun `同一目标连点只发最后一次意图`() = runTest {
        val store = FakeActionStore()
        val seen = mutableListOf<String>()
        val q = queue(store, ActionHandler { action ->
            seen += action.payload
            ActionOutcome.Success
        })

        // 收藏 → 取消 → 收藏，三次点击落在同一个 dedupeKey 上。
        q.enqueueAndWait(request("bookmark"))
        q.enqueueAndWait(request("unbookmark"))
        q.enqueueAndWait(request("bookmark-again"))
        q.start()
        advanceTimeBy(10_000)

        assertEquals(listOf("bookmark-again"), seen)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `coalesce 关掉时同一目标的每次点击都保留`() = runTest {
        val store = FakeActionStore()
        val seen = mutableListOf<String>()
        val q = queue(store, ActionHandler { action ->
            seen += action.payload
            ActionOutcome.Success
        })

        q.enqueueAndWait(request("one").copy(coalesce = false))
        q.enqueueAndWait(request("two").copy(coalesce = false))
        q.start()
        advanceTimeBy(10_000)

        assertEquals(listOf("one", "two"), seen)
    }

    @Test
    fun `撞 429 后整队冷却 而不是只退避失败那条`() = runTest {
        val store = FakeActionStore()
        val ranAt = mutableListOf<Long>()
        var firstCall = true
        val q = queue(store, ActionHandler {
            ranAt += testScheduler.currentTime
            if (firstCall) {
                firstCall = false
                ActionOutcome.Retry(retryAfterMs = 60_000L)
            } else {
                ActionOutcome.Success
            }
        })

        q.enqueueAndWait(request("a", key = "illust:1"))
        q.enqueueAndWait(request("b", key = "illust:2"))
        q.start()

        advanceTimeBy(30_000)
        // 第一条撞了 429，Retry-After 说等 60 秒 —— 排在后面的第二条也不许发。
        assertEquals(listOf(0L), ranAt)

        advanceTimeBy(31_000)
        // 冷却结束，重试第一条（保持原有排队顺序，不被第二条插队）。
        assertEquals(listOf(0L, 60_000L), ranAt)
    }

    @Test
    fun `Retry-After 比指数退避长时以服务端为准`() = runTest {
        val store = FakeActionStore()
        val ranAt = mutableListOf<Long>()
        var calls = 0
        val q = queue(
            store,
            ActionHandler {
                ranAt += testScheduler.currentTime
                calls++
                if (calls == 1) ActionOutcome.Retry(retryAfterMs = 120_000L) else ActionOutcome.Success
            },
            policy = policy(initialCooldownMs = 30_000L),
        )

        q.enqueueAndWait(request("a"))
        q.start()

        advanceTimeBy(60_000)
        assertEquals(listOf(0L), ranAt) // 指数退避只有 30s，但服务端说 120s，听服务端的

        advanceTimeBy(61_000)
        assertEquals(listOf(0L, 120_000L), ranAt)
    }

    @Test
    fun `不可重试的失败不进冷却 也不挡住后面的动作`() = runTest {
        val store = FakeActionStore()
        val ranAt = mutableListOf<Long>()
        val q = queue(store, ActionHandler { action ->
            ranAt += testScheduler.currentTime
            if (action.payload == "gone") ActionOutcome.Fail("作品已删除") else ActionOutcome.Success
        })

        q.enqueueAndWait(request("gone", key = "illust:1"))
        q.enqueueAndWait(request("ok", key = "illust:2"))
        q.start()
        advanceTimeBy(10_000)

        // 第二条只等了一个节流间隔，没有被 30 秒冷却拖住。
        assertEquals(listOf(0L, 2_000L), ranAt)
        assertEquals(1, store.failedCount())
    }

    @Test
    fun `重试次数耗尽后转终态失败并广播 Failed`() = runTest {
        val store = FakeActionStore()
        val events = mutableListOf<ActionEvent>()
        val q = queue(
            store,
            ActionHandler { ActionOutcome.Retry(cause = RuntimeException("boom")) },
            policy = policy(maxAttempts = 3, initialCooldownMs = 1_000L),
        )
        backgroundScope.launch { q.events.collect { events += it } }

        q.enqueueAndWait(request("a"))
        q.start()
        advanceTimeBy(60_000)

        assertEquals(2, events.count { it is ActionEvent.Retrying })
        assertEquals(1, events.count { it is ActionEvent.Failed })
        assertEquals(1, store.failedCount())
        assertNull(store.nextRunnable(Long.MAX_VALUE))
    }

    @Test
    fun `冷启动把上次残留的 RUNNING 复位重跑`() = runTest {
        val store = FakeActionStore()
        store.seedRunning(type, "killed-mid-flight")
        val seen = mutableListOf<String>()
        val q = queue(store, ActionHandler { action ->
            seen += action.payload
            ActionOutcome.Success
        })

        q.start()
        advanceTimeBy(5_000)

        assertEquals(listOf("killed-mid-flight"), seen)
    }

    @Test
    fun `注册表里没有的 type 直接判失败 不会堵住队首`() = runTest {
        val store = FakeActionStore()
        val seen = mutableListOf<String>()
        val q = ActionQueue(
            store = store,
            handlers = mapOf(type to ActionHandler { action ->
                seen += action.payload
                ActionOutcome.Success
            }),
            policy = policy(),
            clock = { testScheduler.currentTime },
            scope = backgroundScope,
            random = Random(0),
        )

        q.enqueueAndWait(ActionRequest("unknown-type", "x:1", "orphan"))
        q.enqueueAndWait(request("ok", key = "illust:2"))
        q.start()
        advanceTimeBy(10_000)

        assertEquals(listOf("ok"), seen)
        assertEquals(1, store.failedCount())
    }

    @Test
    fun `gate 关着时只睡不取 打开后继续`() = runTest {
        val store = FakeActionStore()
        val ranAt = mutableListOf<Long>()
        var loggedIn = false
        val q = queue(
            store,
            ActionHandler {
                ranAt += testScheduler.currentTime
                ActionOutcome.Success
            },
            gate = { loggedIn },
        )

        q.enqueueAndWait(request("a"))
        q.start()
        advanceTimeBy(120_000)
        assertTrue("未登录时不该发请求", ranAt.isEmpty())

        loggedIn = true
        q.resume() // 主动踢一脚唤醒，省得等兜底轮询
        advanceTimeBy(5_000)
        assertEquals(1, ranAt.size)
    }

    @Test
    fun `手动重试把终态失败的行重新排队`() = runTest {
        val store = FakeActionStore()
        var shouldFail = true
        val ranAt = mutableListOf<Long>()
        val q = queue(store, ActionHandler {
            ranAt += testScheduler.currentTime
            if (shouldFail) ActionOutcome.Fail("nope") else ActionOutcome.Success
        })

        q.enqueueAndWait(request("a"))
        q.start()
        advanceTimeBy(5_000)
        assertEquals(1, store.failedCount())

        shouldFail = false
        assertEquals(1, q.retryAllFailed())
        advanceTimeBy(5_000)

        assertEquals(2, ranAt.size)
        assertEquals(0, store.failedCount())
        assertTrue(store.rows.isEmpty())
    }
}
