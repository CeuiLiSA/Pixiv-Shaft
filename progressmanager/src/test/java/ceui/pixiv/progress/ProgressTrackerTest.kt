package ceui.pixiv.progress

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 拦截器语义的集成测试：真 OkHttp + MockWebServer。
 *
 * 节流细节在 [ProgressResponseBodyTest] 里用假 Source 精确验过，这里的时钟一律停住
 * （只发首个事件和完成事件）或设为 0 间隔（每次 read 都发），事件数量才不依赖 socket 分片。
 * 体积一律 ≥ [MULTI_READ_BODY_SIZE]：okio 单次从 socket 读不超过一个 8 KB segment，
 * 保证响应体一定分多次 read，「首个事件不是完成事件」才是确定的。
 */
class ProgressTrackerTest {

    private val server = MockWebServer()
    private val clock = FakeClock()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun tracker(
        refreshIntervalMs: Long = 150L,
        onListenerError: (Exception) -> Unit = { throw it },
    ): ProgressTracker = ProgressTracker(refreshIntervalMs, clock, onListenerError)

    private fun client(tracker: ProgressTracker): OkHttpClient =
        tracker.install(OkHttpClient.Builder()).build()

    private fun body(size: Int): MockResponse = MockResponse().setBody(Buffer().write(bytes(size)))

    private fun OkHttpClient.fetch(url: String): ByteArray =
        newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertTrue("unexpected ${response.code}", response.isSuccessful)
            response.body!!.bytes()
        }

    private fun assertWellFormed(events: List<DownloadProgress>, expectedSize: Long) {
        assertTrue("expected at least one event", events.isNotEmpty())
        events.zipWithNext().forEach { (a, b) ->
            assertTrue("bytesRead went backwards: $a -> $b", b.bytesRead >= a.bytesRead)
            assertFalse("event after done: $a -> $b", a.isDone)
        }
        events.forEach { assertEquals(expectedSize, it.contentLength) }
        assertEquals(DownloadProgress(expectedSize, expectedSize, isDone = true), events.last())
        assertEquals(1, events.count { it.isDone })
    }

    // ── 基本路径 ──────────────────────────────────────────────────────────

    @Test
    fun `tracked url receives a first event and a done event with intact bytes`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        val received = tracker.track(url, listener).use { client(tracker).fetch(url) }

        assertTrue(bytes(MULTI_READ_BODY_SIZE).contentEquals(received))
        assertWellFormed(listener.events, MULTI_READ_BODY_SIZE.toLong())
        // 时钟停着：首次 read 报一次，之后全被节流，直到读满
        assertEquals(2, listener.events.size)
        assertFalse(listener.events.first().isDone)
        assertTrue(listener.events.first().bytesRead in 1 until MULTI_READ_BODY_SIZE)
        assertEquals(0, tracker.trackedUrlCount)
    }

    @Test
    fun `zero interval reports every read and the sequence is well formed`() {
        val tracker = tracker(refreshIntervalMs = 0L)
        val listener = RecordingListener()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        tracker.track(url, listener).use { client(tracker).fetch(url) }

        assertWellFormed(listener.events, MULTI_READ_BODY_SIZE.toLong())
        assertTrue("expected several reads for a ${MULTI_READ_BODY_SIZE}B body", listener.events.size >= 3)
    }

    @Test
    fun `untracked url is left alone and bytes are intact`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val tracked = server.url("/tracked").toString()
        val other = server.url("/other").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        val received = tracker.track(tracked, listener).use { client(tracker).fetch(other) }

        assertTrue(bytes(MULTI_READ_BODY_SIZE).contentEquals(received))
        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun `subscription closed before the request yields no events`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        tracker.track(url, listener).close()
        client(tracker).fetch(url)

        assertTrue(listener.events.isEmpty())
        assertEquals(0, tracker.trackedUrlCount)
    }

    @Test
    fun `chunked transfer reports null length and completes on EOF`() {
        val tracker = tracker(refreshIntervalMs = 0L)
        val listener = RecordingListener()
        val url = server.url("/chunked").toString()
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes(MULTI_READ_BODY_SIZE)), 4096))

        val received = tracker.track(url, listener).use { client(tracker).fetch(url) }

        assertTrue(bytes(MULTI_READ_BODY_SIZE).contentEquals(received))
        listener.events.forEach { assertEquals(null, it.contentLength) }
        assertEquals(
            DownloadProgress(MULTI_READ_BODY_SIZE.toLong(), null, isDone = true),
            listener.events.last(),
        )
        assertEquals(1, listener.doneEvents.size)
    }

    // ── 键的语义 ──────────────────────────────────────────────────────────

    @Test
    fun `redirect is keyed on the original url and the 3xx hop is not reported`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val start = server.url("/start").toString()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        val received = tracker.track(start, listener).use { client(tracker).fetch(start) }

        assertTrue(bytes(MULTI_READ_BODY_SIZE).contentEquals(received))
        assertEquals("/start", server.takeRequest().path)
        assertEquals("/final", server.takeRequest().path)
        // 302 那一跳 body 为空：若被包装会先冒出一个 (0, 0, done=true)，把序列打成两段
        assertWellFormed(listener.events, MULTI_READ_BODY_SIZE.toLong())
    }

    @Test
    fun `tracking the redirect target instead of the original url yields nothing`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val start = server.url("/start").toString()
        val target = server.url("/final").toString()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        tracker.track(target, listener).use { client(tracker).fetch(start) }

        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun `non-2xx responses are not reported`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val url = server.url("/missing").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE).setResponseCode(404))

        tracker.track(url, listener).use {
            client(tracker).newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertEquals(404, response.code)
                assertEquals(MULTI_READ_BODY_SIZE, response.body!!.bytes().size)
            }
        }

        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun `url is normalised so scheme and host case do not matter`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val shouted = "HTTP://${server.hostName.uppercase()}:${server.port}/Case/Sensitive/Path"
        val requested = server.url("/Case/Sensitive/Path").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        tracker.track(shouted, listener).use { client(tracker).fetch(requested) }

        assertWellFormed(listener.events, MULTI_READ_BODY_SIZE.toLong())
    }

    @Test
    fun `path case is significant`() {
        val tracker = tracker()
        val listener = RecordingListener()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        tracker.track(server.url("/a.png").toString(), listener).use {
            client(tracker).fetch(server.url("/A.png").toString())
        }

        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun `events are routed to the listener of their own url only`() {
        val tracker = tracker()
        val a = RecordingListener()
        val b = RecordingListener()
        val urlA = server.url("/a").toString()
        val urlB = server.url("/b").toString()
        val sizeA = MULTI_READ_BODY_SIZE
        val sizeB = MULTI_READ_BODY_SIZE * 2
        server.enqueue(body(sizeA))
        server.enqueue(body(sizeB))

        tracker.track(urlA, a).use {
            tracker.track(urlB, b).use {
                client(tracker).fetch(urlA)
                val aEventsAfterA = a.events.size
                assertTrue(b.events.isEmpty())

                client(tracker).fetch(urlB)
                assertEquals(aEventsAfterA, a.events.size)
            }
        }

        assertWellFormed(a.events, sizeA.toLong())
        assertWellFormed(b.events, sizeB.toLong())
    }

    // ── 多订阅者 ──────────────────────────────────────────────────────────

    @Test
    fun `several listeners on one url all get the identical sequence`() {
        val tracker = tracker(refreshIntervalMs = 0L)
        val first = RecordingListener()
        val second = RecordingListener()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        tracker.track(url, first).use {
            tracker.track(url, second).use { client(tracker).fetch(url) }
        }

        assertWellFormed(first.events, MULTI_READ_BODY_SIZE.toLong())
        assertEquals(first.events, second.events)
        assertEquals(0, tracker.trackedUrlCount)
    }

    @Test
    fun `same listener tracked twice is called twice and closing one keeps the other`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        val one = tracker.track(url, listener)
        val two = tracker.track(url, listener)
        client(tracker).fetch(url)
        assertEquals(4, listener.events.size) // 停表时钟：2 个事件 × 2 份订阅
        assertEquals(1, tracker.trackedUrlCount)

        one.close()
        listener.events.clear()
        client(tracker).fetch(url)
        assertEquals(2, listener.events.size)
        assertEquals(1, tracker.trackedUrlCount)

        two.close()
        assertEquals(0, tracker.trackedUrlCount)
    }

    @Test
    fun `closing a subscription is idempotent and does not evict a sibling`() {
        val tracker = tracker()
        val keep = RecordingListener()
        val url = server.url("/img.png").toString()
        val doomed = tracker.track(url) { }
        val kept = tracker.track(url, keep)

        doomed.close()
        doomed.close()
        doomed.close()

        assertTrue(doomed.isClosed)
        assertFalse(kept.isClosed)
        assertEquals(1, tracker.trackedUrlCount)
        server.enqueue(body(MULTI_READ_BODY_SIZE))
        client(tracker).fetch(url)
        assertWellFormed(keep.events, MULTI_READ_BODY_SIZE.toLong())
        kept.close()
        assertEquals(0, tracker.trackedUrlCount)
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────

    @Test
    fun `closing the subscription from inside the callback stops further events immediately`() {
        val tracker = tracker(refreshIntervalMs = 0L)
        val events = CopyOnWriteArrayList<DownloadProgress>()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        lateinit var subscription: ProgressSubscription
        subscription = tracker.track(url) { progress ->
            events += progress
            subscription.close()
        }
        val received = client(tracker).fetch(url)

        assertTrue(bytes(MULTI_READ_BODY_SIZE).contentEquals(received))
        assertEquals(1, events.size)
        assertFalse(events.single().isDone)
        assertEquals(0, tracker.trackedUrlCount)
    }

    @Test
    fun `cancelling the call mid-stream never produces a done event`() {
        val tracker = tracker(refreshIntervalMs = 0L)
        val listener = RecordingListener()
        val url = server.url("/slow").toString()
        // 每 100ms 才放 8 KB：取消那一刻 socket 里不可能已经躺着整个 body
        server.enqueue(body(MULTI_READ_BODY_SIZE * 4).throttleBody(8192, 100, TimeUnit.MILLISECONDS))

        lateinit var call: Call
        val subscription = tracker.track(url) { progress ->
            listener.onProgress(progress)
            call.cancel()
        }
        call = client(tracker).newCall(Request.Builder().url(url).build())

        assertThrows(IOException::class.java) {
            call.execute().use { it.body!!.bytes() }
        }
        subscription.close()

        assertTrue(listener.events.isNotEmpty())
        assertTrue("cancelled download must not report done", listener.doneEvents.isEmpty())
    }

    // ── 订阅者出错 ────────────────────────────────────────────────────────

    @Test
    fun `listener failure goes to onListenerError and the download completes for everyone else`() {
        val errors = CopyOnWriteArrayList<Exception>()
        val tracker = tracker(refreshIntervalMs = 0L, onListenerError = { errors += it })
        val healthy = RecordingListener()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))
        val boom = IllegalStateException("listener bug")

        val received = tracker.track(url) { throw boom }.use {
            tracker.track(url, healthy).use { client(tracker).fetch(url) }
        }

        assertTrue(bytes(MULTI_READ_BODY_SIZE).contentEquals(received))
        assertWellFormed(healthy.events, MULTI_READ_BODY_SIZE.toLong())
        assertEquals(healthy.events.size, errors.size)
        errors.forEach { assertSame(boom, it) }
    }

    @Test
    fun `an Error thrown by a listener bypasses onListenerError entirely`() {
        val errors = CopyOnWriteArrayList<Exception>()
        val tracker = tracker(refreshIntervalMs = 0L, onListenerError = { errors += it })
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        tracker.track(url) { throw OutOfMemoryError("simulated") }.use {
            val thrown = assertThrows(OutOfMemoryError::class.java) { client(tracker).fetch(url) }
            assertEquals("simulated", thrown.message)
        }

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `by default a listener failure fails the read loudly`() {
        val tracker = tracker()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))

        tracker.track(url) { throw IllegalStateException("listener bug") }.use {
            val thrown = assertThrows(IllegalStateException::class.java) { client(tracker).fetch(url) }
            assertEquals("listener bug", thrown.message)
        }
    }

    // ── 装配 ──────────────────────────────────────────────────────────────

    @Test
    fun `install adds the interceptor at the network layer and returns the same builder`() {
        val tracker = tracker()
        val builder = OkHttpClient.Builder()

        val returned = tracker.install(builder)

        assertSame(builder, returned)
        assertEquals(listOf(tracker.interceptor), builder.networkInterceptors())
        assertTrue(builder.interceptors().isEmpty())
    }

    @Test
    fun `clients derived with newBuilder inherit the interceptor`() {
        val tracker = tracker()
        val listener = RecordingListener()
        val url = server.url("/img.png").toString()
        server.enqueue(body(MULTI_READ_BODY_SIZE))
        val derived = client(tracker).newBuilder().build()

        tracker.track(url, listener).use { derived.fetch(url) }

        assertWellFormed(listener.events, MULTI_READ_BODY_SIZE.toLong())
    }

    @Test
    fun `negative refresh interval is rejected`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ProgressTracker(refreshIntervalMs = -1L)
        }
        assertTrue(thrown.message!!.contains("-1"))
    }

    @Test
    fun `monotonic clock never goes backwards`() {
        var previous = Clock.MONOTONIC.nowMs()
        repeat(1_000) {
            val now = Clock.MONOTONIC.nowMs()
            assertTrue(now >= previous)
            previous = now
        }
    }

    // ── 并发 ──────────────────────────────────────────────────────────────

    /**
     * 前身的订阅表是无锁的 WeakHashMap，dispatcher 线程上的 `get` 会顺手清理弱键，
     * 与另一个线程的 `put` 撞在一起就是链表打环。这里把「注册 / 请求 / 解除」压在多个线程上跑，
     * 同时另起线程对无关 URL 高频注册解除，任何一次崩溃或漏报都会让断言失败。
     */
    @Test
    fun `concurrent track fetch and untrack across threads deliver exactly one done per request`() {
        val tracker = tracker(refreshIntervalMs = 0L)
        val bodySize = 16 * 1024
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = body(bodySize)
        }
        val client = tracker.install(
            OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 64 }),
        ).build()

        val workers = 8
        val perWorker = 25
        val pool = Executors.newFixedThreadPool(workers)
        val failures = CopyOnWriteArrayList<Throwable>()
        val completed = AtomicInteger()
        val start = CountDownLatch(1)
        val stopChurning = AtomicBoolean(false)

        // 陪跑线程：对无关 URL 不停注册 / 解除，专门制造表结构变动。
        // 不进线程池：池要等所有任务结束，它要等池结束，放一起就是互相等。
        val churner = Thread {
            start.await()
            var i = 0
            while (!stopChurning.get()) {
                tracker.track("http://unrelated.invalid/$i") { }.close()
                i++
            }
        }.apply { isDaemon = true; start() }

        repeat(workers) { worker ->
            pool.execute {
                start.await()
                try {
                    repeat(perWorker) { i ->
                        val url = server.url("/w$worker/$i").toString()
                        val listener = RecordingListener()
                        val received = tracker.track(url, listener).use { client.fetch(url) }
                        assertEquals(bodySize, received.size)
                        assertWellFormed(listener.events, bodySize.toLong())
                        completed.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }

        start.countDown()
        pool.shutdown()
        val finished = pool.awaitTermination(60, TimeUnit.SECONDS)
        stopChurning.set(true)
        churner.join(5_000)
        assertTrue("workers did not finish", finished)

        assertTrue(failures.joinToString("\n") { it.stackTraceToString() }, failures.isEmpty())
        assertEquals(workers * perWorker, completed.get())
        assertEquals(0, tracker.trackedUrlCount)
    }

    private companion object {
        /** 8 个 okio segment：再小也得分 8 次以上才读得完。 */
        const val MULTI_READ_BODY_SIZE = 64 * 1024
    }
}
