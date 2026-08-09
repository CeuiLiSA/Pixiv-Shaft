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
        owner: suspend () -> String = { "" },
    ) = ActionQueue(
        store = store,
        handlers = mapOf(type to handler),
        policy = policy,
        clock = { testScheduler.currentTime },
        gate = gate,
        owner = owner,
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
    fun `ACTION 作用域的重试只推迟这一条 不冻住整队`() = runTest {
        val store = FakeActionStore()
        val ranAt = mutableListOf<Pair<Long, String>>()
        val q = queue(store, ActionHandler { action ->
            ranAt += testScheduler.currentTime to action.payload
            // 第一条是永远 400 的坏行；它不该把后面别人的收藏一起冻住。
            if (action.payload == "bad") {
                ActionOutcome.Retry(cause = RuntimeException("HTTP 400"), scope = RetryScope.ACTION)
            } else {
                ActionOutcome.Success
            }
        })

        q.enqueueAndWait(request("bad", key = "illust:1"))
        q.enqueueAndWait(request("good", key = "illust:2"))
        q.start()
        advanceTimeBy(10_000)

        // 坏行退避 30 秒，好行只等了一个最小间隔 —— 换成整队冷却的话它得等 30 秒。
        assertEquals(listOf(0L to "bad", 2_000L to "good"), ranAt)
        assertEquals(30_000L, store.rows.single().notBefore)
    }

    @Test
    fun `断网不消耗重试预算 网络回来后照常发出`() = runTest {
        val store = FakeActionStore()
        val events = mutableListOf<ActionEvent>()
        var offline = true
        val ranAt = mutableListOf<Long>()
        val q = queue(
            store,
            ActionHandler {
                ranAt += testScheduler.currentTime
                if (offline) {
                    ActionOutcome.Retry(
                        cause = java.io.IOException("no route to host"),
                        countsAsAttempt = false,
                    )
                } else {
                    ActionOutcome.Success
                }
            },
            policy = policy(maxAttempts = 3, initialCooldownMs = 30_000L),
        )
        backgroundScope.launch { q.events.collect { events += it } }

        q.enqueueAndWait(request("a"))
        q.start()

        // 断网 20 分钟：按 maxAttempts=3 早该烧成终态失败了，但一次预算都不该被消耗。
        advanceTimeBy(20 * 60_000L)
        assertTrue("断网期间不该判终态失败", events.none { it is ActionEvent.Failed })
        assertEquals(0, store.rows.single().attempt)
        assertTrue("应当一直在重试", ranAt.size > 3)

        offline = false
        advanceTimeBy(60_000L)
        assertTrue("网络回来后必须真的发出去", store.rows.isEmpty())
        assertEquals(1, events.count { it is ActionEvent.Succeeded })
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
        assertEquals(1, store.failedCount(""))
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
        assertEquals(1, store.failedCount(""))
        assertNull(store.nextRunnable(Long.MAX_VALUE, ""))
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
        assertEquals(1, store.failedCount(""))
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
        q.kick() // 主动踢一脚唤醒，省得等兜底轮询（注意不是 resume：闸门开了不代表要解除暂停）
        advanceTimeBy(5_000)
        assertEquals(1, ranAt.size)
    }

    @Test
    fun `踢一脚只唤醒循环 不会解除暂停`() = runTest {
        val store = FakeActionStore()
        val ranAt = mutableListOf<Long>()
        val q = queue(store, ActionHandler {
            ranAt += testScheduler.currentTime
            ActionOutcome.Success
        })

        q.enqueueAndWait(request("a"))
        q.pause()
        q.start()
        advanceTimeBy(120_000)
        assertTrue("暂停期间不该发请求", ranAt.isEmpty())

        // 网络回来 / 登录完成走的就是 kick 这条路。它只该唤醒循环重新评估一次，
        // 绝不能顺手把用户主动按下的暂停解除掉 —— 否则一次网络抖动就把暂停开关废了。
        q.kick()
        advanceTimeBy(120_000)
        assertTrue("kick 不该解除暂停", ranAt.isEmpty())
        assertTrue(q.isPaused.value)

        q.resume()
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
        assertEquals(1, store.failedCount(""))

        shouldFail = false
        assertEquals(1, q.retryAllFailed())
        advanceTimeBy(5_000)

        assertEquals(2, ranAt.size)
        assertEquals(0, store.failedCount(""))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `再点一次不会把正在退避的重试预算清零`() = runTest {
        val store = FakeActionStore()
        val q = queue(
            store,
            ActionHandler { ActionOutcome.Retry(cause = RuntimeException("boom")) },
            policy = policy(maxAttempts = 3, initialCooldownMs = 1_000L),
        )

        q.enqueueAndWait(request("a"))
        q.start()
        advanceTimeBy(100)
        assertEquals(1, store.rows.single().attempt)

        // 用户看红心没动，又点了一次。合并掉的那条已经失败过一次，新行必须继承 ——
        // 否则 attempt 永远回到 0，这条动作到不了 maxAttempts，既不回滚也不提示。
        q.enqueueAndWait(request("a-again"))

        val row = store.rows.single()
        assertEquals("a-again", row.payload)
        assertEquals(1, row.attempt)
        assertEquals(1_000L, row.notBefore)
    }

    @Test
    fun `存储层抛异常不会杀死消费循环`() = runTest {
        val store = FakeActionStore()
        val errors = mutableListOf<String>()
        val ranAt = mutableListOf<Long>()
        val q = ActionQueue(
            store = store,
            handlers = mapOf(type to ActionHandler {
                ranAt += testScheduler.currentTime
                ActionOutcome.Success
            }),
            policy = policy(),
            clock = { testScheduler.currentTime },
            scope = backgroundScope,
            random = Random(0),
            onError = { message, _ -> errors += message },
        )

        q.enqueueAndWait(request("a"))
        store.failWith = { IllegalStateException("disk full") }
        q.start()
        advanceTimeBy(5_000)
        assertTrue("读库炸了不该有动作被执行", ranAt.isEmpty())
        assertTrue("应当上报存储故障", errors.isNotEmpty())

        // 磁盘腾出来之后，消费者必须还活着 —— 否则这个进程剩下的时间里所有收藏
        // 都只写了乐观状态、永远发不出去。
        store.failWith = null
        advanceTimeBy(120_000)
        assertEquals(1, ranAt.size)
    }

    @Test
    fun `整队冷却跨进程恢复 不会重启后原地重撞限流`() = runTest {
        val store = FakeActionStore()
        val ranAt = mutableListOf<Long>()
        // 上个进程撞了 429，冷却到 50 秒；它没来得及给这两条排 notBefore 就被系统回收了。
        store.seedCooldown(50_000L)
        store.seedPending(type, "a", key = "illust:1")
        store.seedPending(type, "b", key = "illust:2")
        val q = queue(store, ActionHandler {
            ranAt += testScheduler.currentTime
            ActionOutcome.Success
        })

        q.start()
        advanceTimeBy(30_000)
        assertTrue("冷却还没过就不许发", ranAt.isEmpty())

        advanceTimeBy(25_000)
        // 冷却到点才放行，随后照常按最小间隔一条条发。
        assertEquals(listOf(50_000L, 52_000L), ranAt)
    }

    @Test
    fun `失败时若同目标还压着更新的意图 则标记为已被顶替`() = runTest {
        val store = FakeActionStore()
        val events = mutableListOf<ActionEvent.Failed>()
        val q = queue(store, ActionHandler { action ->
            if (action.payload == "old") ActionOutcome.Fail("gone") else ActionOutcome.Success
        })
        backgroundScope.launch {
            q.events.collect { if (it is ActionEvent.Failed) events += it }
        }

        q.enqueueAndWait(request("old").copy(coalesce = false))
        q.enqueueAndWait(request("new").copy(coalesce = false))
        q.start()
        advanceTimeBy(10_000)

        // 收藏→取消→收藏之后当前值和失败那条恰好相等，比值判断会误回滚；
        // 判据只能是「同 dedupeKey 上还有没有待执行的行」。
        assertEquals(1, events.size)
        assertTrue("同目标还有 PENDING，UI 不该回滚", events.single().supersededByPending)
    }

    @Test
    fun `队列只发当前账号的行`() = runTest {
        val store = FakeActionStore()
        val seen = mutableListOf<String>()
        var uid = "B"
        val q = queue(
            store,
            ActionHandler { action ->
                seen += action.payload
                ActionOutcome.Success
            },
            owner = { uid },
        )

        // A 退登前排下的收藏，绝不该用 B 的 token 发出去。
        store.seedPending(type, "A-bookmark", key = "illust:1", owner = "A")
        q.start()
        advanceTimeBy(120_000)
        assertTrue(seen.isEmpty())

        uid = "A"
        q.kick()
        advanceTimeBy(5_000)
        assertEquals(listOf("A-bookmark"), seen)
    }

    @Test
    fun `离谱的 Retry-After 按上限采信 不会把队列冻到进程结束`() = runTest {
        val store = FakeActionStore()
        val q = queue(store, ActionHandler {
            ActionOutcome.Retry(retryAfterMs = 86_400_000L) // 服务端说等一天
        })

        q.enqueueAndWait(request("a"))
        q.start()
        advanceTimeBy(100)

        assertEquals(QueuePolicy().maxRetryAfterMs, store.rows.single().notBefore)
    }

    @Test
    fun `连点的入队顺序等于调用顺序`() = runTest {
        val store = FakeActionStore()
        val seen = mutableListOf<String>()
        val q = queue(store, ActionHandler { action ->
            seen += action.payload
            ActionOutcome.Success
        })

        // 走 fire-and-forget 的 enqueue（UI 实际用的那条路），合并之后必须留下最后一次意图。
        q.enqueue(request("bookmark"))
        q.enqueue(request("unbookmark"))
        q.enqueue(request("bookmark-again"))
        q.start()
        advanceTimeBy(10_000)

        assertEquals(listOf("bookmark-again"), seen)
    }
}
