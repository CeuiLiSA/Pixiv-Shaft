package ceui.pixiv.banner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BannerQueueTest {

    @Test
    fun `polls higher priority first and preserves FIFO within a priority`() {
        val queue = BannerQueue(maxSize = 8)
        queue.submit(request("low", BannerPriority.LOW))
        queue.submit(request("normal-1", BannerPriority.NORMAL))
        queue.submit(request("critical", BannerPriority.CRITICAL))
        queue.submit(request("normal-2", BannerPriority.NORMAL))

        assertEquals("critical", queue.pollNext()?.id)
        assertEquals("normal-1", queue.pollNext()?.id)
        assertEquals("normal-2", queue.pollNext()?.id)
        assertEquals("low", queue.pollNext()?.id)
        assertNull(queue.pollNext())
    }

    @Test
    fun `full queue accepts higher priority and evicts its lowest priority tail`() {
        val queue = BannerQueue(maxSize = 2)
        queue.submit(request("low-1", BannerPriority.LOW))
        queue.submit(request("low-2", BannerPriority.LOW))

        val outcome = queue.submit(request("high", BannerPriority.HIGH))

        assertEquals(BannerQueue.SubmitOutcome.AcceptedWithOverflow("low-2"), outcome)
        assertEquals(listOf("high", "low-1"), listOfNotNull(queue.pollNext()?.id, queue.pollNext()?.id))
    }

    @Test
    fun `full queue rejects request that cannot outrank its tail`() {
        val queue = BannerQueue(maxSize = 1)
        queue.submit(request("high", BannerPriority.HIGH))

        val outcome = queue.submit(request("normal", BannerPriority.NORMAL))

        assertEquals(
            BannerQueue.SubmitOutcome.Dropped(DropCause.QUEUE_FULL),
            outcome,
        )
        assertEquals("high", queue.pollNext()?.id)
    }

    @Test
    fun `replace targets both pending and currently presented requests`() {
        val queue = BannerQueue(maxSize = 4)
        queue.submit(request("pending-old", dedupKey = "message"))

        assertEquals(
            BannerQueue.SubmitOutcome.Replaced("pending-old"),
            queue.submit(
                request(
                    id = "pending-new",
                    policy = BannerDisplayPolicy.Replace,
                    dedupKey = "message",
                ),
            ),
        )

        val current = request("current", dedupKey = "current-key")
        queue.markPresenting(current)
        assertEquals(
            BannerQueue.SubmitOutcome.ReplacedCurrent("current"),
            queue.submit(
                request(
                    id = "current-new",
                    policy = BannerDisplayPolicy.Replace,
                    dedupKey = "current-key",
                ),
            ),
        )
    }

    private fun request(
        id: String,
        priority: BannerPriority = BannerPriority.NORMAL,
        policy: BannerDisplayPolicy = BannerDisplayPolicy.Enqueue,
        dedupKey: String? = null,
    ): BannerRequest = BannerRequest.Text(
        id = id,
        title = id,
        priority = priority,
        policy = policy,
        dedupKey = dedupKey,
    )
}
