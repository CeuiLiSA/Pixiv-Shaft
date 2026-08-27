package ceui.pixiv.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 并发 401 时只能有一个线程真正拿 refresh_token 去换——pixiv 会轮换 refresh_token，
 * 第二个用同一个旧 refresh_token 的请求会被拒，进而被误判成「凭证吊销」强制登出。
 */
class SingleFlightTokenRefresherTest {

    @Test
    fun `N threads with the same stale token trigger exactly one refresh`() {
        val token = AtomicReference("old")
        val refreshCalls = AtomicInteger()
        val threads = 16
        val allInside = CountDownLatch(threads)
        val refresher = SingleFlightTokenRefresher(
            currentToken = { token.get() },
            doRefresh = {
                refreshCalls.incrementAndGet()
                // 让其它线程有机会在刷新进行中排到锁上
                Thread.sleep(50)
                token.set("new")
                "new"
            },
        )

        val pool = Executors.newFixedThreadPool(threads)
        val results = (1..threads).map {
            pool.submit<String?> {
                allInside.countDown()
                allInside.await(5, TimeUnit.SECONDS)
                refresher.refresh("old")
            }
        }.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, refreshCalls.get())
        assertEquals(List(threads) { "new" }, results)
    }

    @Test
    fun `a request that used an already-replaced token gets the fresh one without refreshing`() {
        val refreshCalls = AtomicInteger()
        val refresher = SingleFlightTokenRefresher(
            currentToken = { "new" },
            doRefresh = { refreshCalls.incrementAndGet(); "newer" },
        )
        assertEquals("new", refresher.refresh("old"))
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun `logged out during the wait returns null instead of throwing`() {
        val refreshCalls = AtomicInteger()
        val refresher = SingleFlightTokenRefresher(
            currentToken = { null },
            doRefresh = { refreshCalls.incrementAndGet(); "x" },
        )
        assertNull(refresher.refresh("old"))
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun `threads queued behind a failed refresh give up instead of retrying in series`() {
        val refreshCalls = AtomicInteger()
        val threads = 8
        val allInside = CountDownLatch(threads)
        val refresher = SingleFlightTokenRefresher(
            currentToken = { "old" },
            doRefresh = { refreshCalls.incrementAndGet(); Thread.sleep(50); null },
        )
        val pool = Executors.newFixedThreadPool(threads)
        val results = (1..threads).map {
            pool.submit<String?> {
                allInside.countDown()
                allInside.await(5, TimeUnit.SECONDS)
                refresher.refresh("old")
            }
        }.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, refreshCalls.get())
        assertEquals(List<String?>(threads) { null }, results)

        // 一次新的 401 进来（不是排队的那批）要能再试
        assertNull(refresher.refresh("old"))
        assertEquals(2, refreshCalls.get())
    }

    @Test
    fun `refresh failure propagates as null so the caller returns the original 400`() {
        val refresher = SingleFlightTokenRefresher(
            currentToken = { "old" },
            doRefresh = { null },
        )
        assertNull(refresher.refresh("old"))
    }
}
