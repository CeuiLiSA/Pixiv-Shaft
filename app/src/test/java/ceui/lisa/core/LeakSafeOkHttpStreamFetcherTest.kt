package ceui.lisa.core

import com.bumptech.glide.Priority
import com.bumptech.glide.load.data.DataFetcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * [LeakSafeOkHttpStreamFetcher] 的行为验证，核心是上游 OkHttpStreamFetcher 的泄漏场景：
 * 响应到达后流被 Glide 引擎暂存（SourceGenerator.dataToCache），随后请求被取消
 * —— 上游没有任何人关闭 body（OkHttp 报 "connection was leaked"），本实现必须在
 * cancel() 里就地关闭。
 */
class LeakSafeOkHttpStreamFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun newFetcher(): LeakSafeOkHttpStreamFetcher {
        return LeakSafeOkHttpStreamFetcher(
            client,
            server.url("/img/test.jpg").toString(),
            mapOf("Referer" to "https://app-api.pixiv.net/"),
            // cancel() 现在把关流挪到后台线程；单测用同线程 executor 保持关闭后可立即断言
            Executor { it.run() },
        )
    }

    /** 模拟 SourceGenerator：拿到流只暂存不消费（dataToCache 的 park 行为）。 */
    private class ParkingCallback : DataFetcher.DataCallback<InputStream> {
        val ready = CountDownLatch(1)
        var stream: InputStream? = null
        var error: Exception? = null

        override fun onDataReady(data: InputStream?) {
            stream = data
            ready.countDown()
        }

        override fun onLoadFailed(e: Exception) {
            error = e
            ready.countDown()
        }
    }

    @Test
    fun `正常加载 - 送达的流可读出完整 body 且带请求头`() {
        server.enqueue(MockResponse().setBody("hello pixiv"))
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))

        assertNull(callback.error)
        val bytes = checkNotNull(callback.stream).readBytes()
        assertEquals("hello pixiv", String(bytes))
        assertEquals("https://app-api.pixiv.net/", server.takeRequest().getHeader("Referer"))
        fetcher.cleanup()
    }

    /**
     * 泄漏复现场景：响应已到达、流被引擎暂存，随后 cancel。
     * 上游实现此时无人关 body（连接被 GC 后 OkHttp 报 leak），
     * 本实现必须在 cancel() 里关闭 —— 关闭后的流再读直接抛异常。
     */
    @Test
    fun `响应到达后取消 - body 被关闭不泄漏连接`() {
        server.enqueue(MockResponse().setBody("0123456789"))
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
        val stream = checkNotNull(callback.stream)

        // 引擎视角：数据 park 在 dataToCache，还没人读 —— 此刻请求被 clear
        fetcher.cancel()

        val readAfterCancel = runCatching { stream.read() }
        assertTrue(
            "cancel 后流必须已被关闭（读取应抛异常），否则连接泄漏",
            readAfterCancel.isFailure,
        )
    }

    /**
     * 回归 NetworkOnMainThreadException：cancel() 关 OkHttp body 会读/写 socket，必须挪出调用线程
     * （生产里 cancel() 由 Glide.clear() 在主线程触发）。这里用「只捕获不执行」的 executor 验证
     * cancel() 没有在调用线程同步关流，且任务真正跑起来后流才被关闭。
     */
    @Test
    fun `取消时关流交给 executor - 不在调用线程同步关闭`() {
        server.enqueue(MockResponse().setBody("0123456789"))
        var pending: Runnable? = null
        val fetcher = LeakSafeOkHttpStreamFetcher(
            client,
            server.url("/img/test.jpg").toString(),
            emptyMap(),
            Executor { pending = it },
        )
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
        val stream = checkNotNull(callback.stream)

        fetcher.cancel()
        // 关流（会读/写 socket 的 drain）被提交给 executor 而非在调用线程同步执行 —— 这就是不碰
        // 主线程网络守卫的保证。（不用 stream.read() 反证「尚未关闭」：cancel() 里的 call.cancel()
        // 是先行的非阻塞 socket close，小 body 未缓存时读本就会失败，与关流是否已执行无关。）
        assertNotNull("cancel() 必须把关流提交给 executor，不能在调用线程同步关闭", pending)

        // 后台任务真正执行后，流被关闭
        pending!!.run()
        assertTrue("关流任务执行后流应已关闭", runCatching { stream.read() }.isFailure)
    }

    /**
     * 回归「Unbalanced enter/exit」崩溃：引擎在另一线程读同一条流写盘缓存的同时 cancel()，
     * 关流的 drain（Util.discard → skipAll → read）与正在进行的 read 撞上同一条 okio source 的
     * AsyncTimeout。修复前 [LeakSafeOkHttpStreamFetcher.closeLocked] 不吞这个 IllegalStateException，
     * 它会从 cancel()（生产里在后台 cleanupExecutor 线程）逃逸直接崩掉进程。
     * 用同线程 executor 让 cancel() 在本测试线程同步关流，直接断言它不抛出。
     */
    @Test
    fun `取消撞上引擎正在读同一条流 - 关流 drain 不抛崩溃`() {
        repeat(30) { round ->
            server.enqueue(
                MockResponse()
                    // 够大：一次读不完，读会阻塞在节流的 socket read 上（AsyncTimeout entered）
                    .setBody("x".repeat(64 * 1024))
                    .throttleBody(4 * 1024, 5, TimeUnit.MILLISECONDS),
            )
            val fetcher = LeakSafeOkHttpStreamFetcher(
                client,
                server.url("/img/test.jpg").toString(),
                emptyMap(),
                // 同线程关流：closeLocked 的异常若没吞，会直接在本测试线程抛出
                Executor { it.run() },
            )
            val callback = ParkingCallback()
            fetcher.loadData(Priority.NORMAL, callback)
            assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
            val stream = checkNotNull(callback.stream)

            // 引擎视角：另一线程持续读流写盘缓存
            val reading = CountDownLatch(1)
            val reader = Thread {
                val buf = ByteArray(8 * 1024)
                reading.countDown()
                try {
                    while (stream.read(buf) != -1) { /* 写盘缓存：持续读到 socket 阻塞 */ }
                } catch (_: Throwable) {
                    // cancel() 关掉 socket 后读会抛，属预期
                }
            }
            reader.start()
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            Thread.sleep(15) // 让 read 真正进入阻塞的 socket read

            val thrown = runCatching {
                fetcher.cancel() // 修复前：这里抛 IllegalStateException("Unbalanced enter/exit")
            }.exceptionOrNull()
            // 无论断言成败都收好 reader，别把孤儿线程/半开连接漏进下一个用例
            reader.interrupt()
            reader.join(5_000)
            if (thrown != null) {
                fail("第 $round 轮 cancel() 不应抛出（关流 drain 撞并发读）：${thrown.stackTraceToString()}")
            }
        }
    }

    /**
     * 回归 okio SegmentPool 被写坏（线上 AIOOBE：okio checkOffsetAndCount / AsyncTimeout.write，
     * 连尚未入池的新连接 createTunnel 都会中招）：cancel() 的后台关流 drain 与引擎的 read 同时操作
     * 同一个 okio Buffer，同一 Segment 被两次回收进全局池。交出去的流必须让读与关互斥 ——
     * 这里用会探测重入的 delegate 直接断言关流不会与进行中的 read 交叠，且关后再读抛 IOException。
     */
    @Test
    fun `交出去的流 - 关流等进行中的 read 退出且关后读抛 IOException`() {
        val inRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        var overlapped = false
        var reading = false
        val delegate = object : InputStream() {
            override fun read(): Int {
                reading = true
                inRead.countDown()
                releaseRead.await(5, TimeUnit.SECONDS)
                reading = false
                return 'x'.code
            }

            override fun close() {
                if (reading) overlapped = true
            }
        }
        val stream = LeakSafeOkHttpStreamFetcher.ReadCloseExclusiveInputStream(delegate)

        val reader = Thread { stream.read() }
        reader.start()
        assertTrue(inRead.await(5, TimeUnit.SECONDS))
        val closer = Thread { stream.close() }
        closer.start()
        Thread.sleep(50) // 给 closer 抢在 read 退出前闯进 delegate.close() 的机会
        releaseRead.countDown()
        reader.join(5_000)
        closer.join(5_000)

        assertTrue("关流不能与进行中的 read 交叠操作同一条 okio source", !overlapped)
        assertTrue(runCatching { stream.read() }.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun `响应到达后正常 cleanup - 幂等且不影响 cancel 再次关闭`() {
        server.enqueue(MockResponse().setBody("abc"))
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
        checkNotNull(callback.stream).readBytes()

        // cleanup / cancel 以任意顺序重复调用都不该抛异常
        fetcher.cleanup()
        fetcher.cancel()
        fetcher.cleanup()
    }

    @Test
    fun `请求中途取消 - 以失败上报且不挂起`() {
        // 不 enqueue 响应：MockWebServer 挂住请求，模拟慢网络下的取消
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        fetcher.cancel()

        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
        assertNull(callback.stream)
        assertNotNull(callback.error)
    }

    @Test
    fun `HTTP 错误码 - 按失败上报`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))

        assertNull(callback.stream)
        assertNotNull(callback.error)
        fetcher.cleanup()
    }
}
