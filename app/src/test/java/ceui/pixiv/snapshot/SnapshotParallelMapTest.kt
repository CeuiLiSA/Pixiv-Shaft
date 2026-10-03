package ceui.pixiv.snapshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [parallelMapOrdered] 的语义契约：**保序**、**宽度受控**、**失败取消兄弟**。
 *
 * 这三条都是快照逐页写盘的正确性前提：
 * - 保序决定 assets / pagePaths 的条目顺序（生成的 JSON 要逐字节可复现，排障时 diff 才有意义）；
 * - 宽度决定联网那一段会不会对源站打出突发；
 * - 失败取消兄弟决定「一张图失败 → 整份快照作废」时不会白做完整批。
 */
class SnapshotParallelMapTest {

    /** 保序：后发的先完成也不能改结果顺序。 */
    @Test
    fun `results keep input order even when later items finish first`() = runBlocking(Dispatchers.Default) {
        val items = (0 until 8).toList()
        val result = parallelMapOrdered(items, parallelism = 8) { item ->
            delay((8 - item) * 10L)
            item * 2
        }
        assertEquals(items.map { it * 2 }, result)
    }

    /** 宽度受控：并发真的上去了，但绝不越过给定宽度。 */
    @Test
    fun `concurrency rises above one but never exceeds the requested width`() = runBlocking(Dispatchers.Default) {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        parallelMapOrdered((0 until 16).toList(), parallelism = 4) {
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(20)
            running.decrementAndGet()
        }
        assertTrue("应当真的并行，peak=$peak", peak.get() > 1)
        assertTrue("不得超过宽度 4，peak=$peak", peak.get() <= 4)
    }

    /** 宽度小于 1 退化成串行，而不是抛异常。 */
    @Test
    fun `a width below one degrades to serial`() = runBlocking(Dispatchers.Default) {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        parallelMapOrdered((0 until 4).toList(), parallelism = 0) {
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(10)
            running.decrementAndGet()
        }
        assertEquals(1, peak.get())
    }

    /** 任一项失败：异常原样抛出，其余兄弟不会跑完。 */
    @Test
    fun `a failure propagates as is and stops the rest of the batch`() {
        val completed = AtomicInteger()
        val error = runCatching {
            runBlocking(Dispatchers.Default) {
                parallelMapOrdered((0 until 6).toList(), parallelism = 2) { item ->
                    if (item == 2) throw IllegalStateException("boom")
                    delay(50)
                    completed.incrementAndGet()
                }
            }
        }.exceptionOrNull()

        assertTrue("应当把原始异常抛出来，而不是包一层，实际=$error", error is IllegalStateException)
        assertEquals("boom", error!!.message)
        // 实际只有最多两个持有许可的项能跑完（宽度 2），其余在取消时就停了。
        assertTrue("失败之后不该整批跑完，completed=${completed.get()}", completed.get() < 6)
    }

    /** 失败不污染下一次调用：宽度闸是每次调用各自的，没有跨调用的状态。 */
    @Test
    fun `a failed batch does not poison the next one`() = runBlocking(Dispatchers.Default) {
        runCatching {
            parallelMapOrdered(listOf(1), parallelism = 2) { throw IllegalStateException("boom") }
        }
        assertEquals(listOf(2), parallelMapOrdered(listOf(1), parallelism = 2) { it * 2 })
    }
}