package ceui.pixiv.safe.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RefreshCoordinatorTest {

    @Test
    fun `concurrent stale requests perform exactly one refresh`() {
        val token = AtomicReference("old")
        val calls = AtomicInteger()
        val threads = 16
        val ready = CountDownLatch(threads)
        val coordinator = RefreshCoordinator(
            currentAccessToken = { token.get() },
            performRefresh = {
                calls.incrementAndGet()
                Thread.sleep(50)
                token.set("new")
                "new"
            },
        )

        val pool = Executors.newFixedThreadPool(threads)
        val results = (1..threads).map {
            pool.submit<String?> {
                ready.countDown()
                ready.await(5, TimeUnit.SECONDS)
                coordinator.refresh("old")
            }
        }.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, calls.get())
        assertEquals(List(threads) { "new" }, results)
    }

    @Test
    fun `already replaced token uses fast path`() {
        val calls = AtomicInteger()
        val coordinator = RefreshCoordinator(
            currentAccessToken = { "new" },
            performRefresh = { calls.incrementAndGet(); "newer" },
        )
        assertEquals("new", coordinator.refresh("old"))
        assertEquals(0, calls.get())
    }

    @Test
    fun `waiters do not serially retry a failed refresh`() {
        val calls = AtomicInteger()
        val threads = 8
        val ready = CountDownLatch(threads)
        val coordinator = RefreshCoordinator(
            currentAccessToken = { "old" },
            performRefresh = { calls.incrementAndGet(); Thread.sleep(50); null },
        )
        val pool = Executors.newFixedThreadPool(threads)
        val results = (1..threads).map {
            pool.submit<String?> {
                ready.countDown()
                ready.await(5, TimeUnit.SECONDS)
                coordinator.refresh("old")
            }
        }.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, calls.get())
        assertEquals(List<String?>(threads) { null }, results)
        assertNull(coordinator.refresh("old"))
        assertEquals(2, calls.get())
    }
}
