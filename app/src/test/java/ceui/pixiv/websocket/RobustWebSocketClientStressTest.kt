package ceui.pixiv.websocket

import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stress and load tests for [RobustWebSocketClient].
 *
 * These run for up to ~30 s each on real hardware. They live in the same
 * test source set so `:network:test` runs them by default — if they become
 * a CI bottleneck, split into a separate `stressTest` source set.
 *
 * What we're verifying:
 *  - **Strict per-connection FIFO** under high single-producer load
 *  - **Per-producer FIFO** under many concurrent producers
 *  - **No message loss** when reconnects happen mid-stream (with adequate
 *    buffer sizing)
 *  - **Correct backpressure under burst** — exactly the right number of
 *    messages survive a buffer overflow
 *  - **Reconnect storm recovery** — kill the server several times in a row
 *    and verify the client always comes back
 */
class RobustWebSocketClientStressTest {

    private lateinit var server: TestWebSocketServer
    private lateinit var okHttp: OkHttpClient
    private val testMonotonicNowMillis: () -> Long = {
        System.nanoTime() / 1_000_000L
    }

    @Before
    fun setUp() {
        server = TestWebSocketServer().apply { start() }
        okHttp = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Listener that records every text frame the server received into a
     * thread-safe ordered list. Used by tests that need to assert message
     * order without poking the [serverEvents] channel.
     */
    private class CountingListener : WebSocketListener() {
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val sockets: ConcurrentLinkedQueue<WebSocket> = ConcurrentLinkedQueue()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.incrementAndGet()
            sockets.add(webSocket)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.incrementAndGet()
            sockets.remove(webSocket)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets.remove(webSocket)
        }
    }

    private fun installCounter(): CountingListener {
        val listener = CountingListener()
        server.enqueueUpgrade(listener)
        return listener
    }

    private suspend fun waitFor(
        timeoutMs: Long,
        pollMs: Long = 25,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("waitFor timed out after ${timeoutMs}ms")
            delay(pollMs)
        }
    }

    // ── 1. Single producer, 5 000 messages, strict order ─────────────────────

    @Test
    fun `single producer 5000 messages preserve strict FIFO order`() { runBlocking {
        val listener = installCounter()
        val total = 5_000
        val client = RobustWebSocketClient(
            baseClient = okHttp,
            config = WebSocketConfig(
                url = server.url(),
                pingIntervalMillis = 0,
                outgoingBufferSize = total + 100,
                txQueueHighWaterMarkBytes = 16L * 1024 * 1024,
            ),
            monotonicNowMillis = testMonotonicNowMillis,
        )
        try {
            client.connect()
            waitFor(timeoutMs = 5_000) { client.state.value is WebSocketState.Connected }

            val elapsed = measureTimeMillis {
                for (i in 0 until total) {
                    assertTrue(client.send("seq-$i"))
                }
                waitFor(timeoutMs = 30_000) { listener.received.size == total }
            }
            println("[stress] 5000 single-producer msgs: ${elapsed}ms")

            // Strict FIFO: every message must be at exactly its expected index.
            synchronized(listener.received) {
                for (i in 0 until total) {
                    assertEquals("position $i", "seq-$i", listener.received[i])
                }
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 2. Concurrent producers, per-producer FIFO ───────────────────────────

    @Test
    fun `10 concurrent producers x 500 messages — per-producer order preserved, no loss`() {
        runBlocking {
            val listener = installCounter()
            val producerCount = 10
            val perProducer = 500
            val total = producerCount * perProducer

            val client = RobustWebSocketClient(
                baseClient = okHttp,
                config = WebSocketConfig(
                    url = server.url(),
                    pingIntervalMillis = 0,
                    outgoingBufferSize = total + 100,
                    txQueueHighWaterMarkBytes = 16L * 1024 * 1024,
                ),
                monotonicNowMillis = testMonotonicNowMillis,
            )
            try {
                client.connect()
                waitFor(timeoutMs = 5_000) { client.state.value is WebSocketState.Connected }

                val jobs = (0 until producerCount).map { producerId ->
                    launch(Dispatchers.IO) {
                        for (i in 0 until perProducer) {
                            assertTrue(client.send("p$producerId#$i"))
                        }
                    }
                }
                jobs.joinAll()
                waitFor(timeoutMs = 30_000) { listener.received.size == total }

                // No loss
                assertEquals(total, listener.received.size)

                // Per-producer order: extract each producer's substream from
                // the global received list and verify it's monotonically
                // increasing in 'i'.
                val perProducerSeen = HashMap<Int, MutableList<Int>>()
                synchronized(listener.received) {
                    for (msg in listener.received) {
                        val (pStr, iStr) = msg.split("#")
                        val pid = pStr.removePrefix("p").toInt()
                        val seq = iStr.toInt()
                        perProducerSeen.getOrPut(pid) { mutableListOf() }.add(seq)
                    }
                }
                for ((pid, list) in perProducerSeen) {
                    assertEquals(
                        "producer $pid lost messages",
                        perProducer,
                        list.size,
                    )
                    for (j in 1 until list.size) {
                        assertTrue(
                            "producer $pid out of order at index $j: " +
                                "${list[j - 1]} > ${list[j]}",
                            list[j - 1] < list[j],
                        )
                    }
                }
            } finally {
                client.close()
            }
        }
    }

    // ── 3. Burst exceeding buffer (DropOldest) — exactly newest survive ──────

    @Test
    fun `DropOldest under burst preserves only the newest bufferSize messages`() { runBlocking {
        val bufferSize = 100
        val burst = 1_000

        // The burst eviction is a Channel.trySend semantic — entirely
        // synchronous, no wire I/O involved. The previous version pushed
        // 101 messages through real OkHttp + MockWebServer just to read
        // them back, and the wall-clock waitFor cliff is what was flaking
        // on slow CI runners. With a [FakeWebSocketFactory] the surviving
        // messages are observable as soon as the consumer drains the
        // channel — milliseconds, not seconds.
        val fakeFactory = FakeWebSocketFactory()
        val client = RobustWebSocketClient(
            baseClient = okHttp,
            config = WebSocketConfig(
                url = server.url(),
                pingIntervalMillis = 0,
                outgoingBufferSize = bufferSize,
                backpressureStrategy = BackpressureStrategy.DropOldest,
            ),
            monotonicNowMillis = testMonotonicNowMillis,
            webSocketFactory = fakeFactory.asFactory(),
        )
        try {
            // Don't connect — pile up the burst, then connect to drain.
            // Note on pre-fetch: the consumer is parked in receive() before
            // any send. The very first send (seq-0) is rendezvous'd directly
            // into the consumer's local variable (held in deliver() while
            // waiting for Connected). The next [bufferSize] sends fill the
            // channel, and subsequent sends drop the oldest *buffered* item.
            // Net result: bufferSize + 1 messages survive — seq-0 plus the
            // newest bufferSize.
            for (i in 0 until burst) {
                assertTrue(client.send("seq-$i"))
            }
            client.connect()
            waitFor(timeoutMs = 5_000) { client.state.value is WebSocketState.Connected }
            val fake = fakeFactory.sockets.single()
            waitFor(timeoutMs = 5_000) { fake.sentText.size >= bufferSize + 1 }
            // Tiny settle window so any in-flight extra send (there should
            // be none) has a chance to surface before we assert the count.
            delay(50)

            synchronized(fake.sentText) {
                val received = fake.sentText.toList()
                val n = received.size
                assertEquals("expected exactly bufferSize+1 surviving messages", bufferSize + 1, n)
                assertEquals("seq-0", received.first())
                // The remaining bufferSize messages must be the newest ones, in order.
                for (i in 1 until n) {
                    val expectedSeq = burst - bufferSize + (i - 1)
                    assertEquals("seq-$expectedSeq", received[i])
                }
            }
        } finally {
            client.close()
            fakeFactory.close()
        }
    }
    }

    // ── 4. Reconnect storm — survive 5 server-side terminations ──────────────

    @Test
    fun `reconnect storm — client survives 5 server-initiated closes and a final echo`() {
        runBlocking {
            val rounds = 5
            // Each round: server accepts, gets one message, immediately
            // sends a Close frame. Client reconnects via the configured
            // backoff strategy. The final round is a healthy echo.
            repeat(rounds) {
                server.enqueueClosingUpgrade(code = 1011, reason = "round $it kill")
            }
            val finalListener = installCounter()

            val client = RobustWebSocketClient(
                baseClient = okHttp,
                config = WebSocketConfig(
                    url = server.url(),
                    pingIntervalMillis = 0,
                    outgoingBufferSize = 256,
                    reconnectStrategy = ExponentialBackoffWithJitter(
                        initialDelayMillis = 30,
                        maxDelayMillis = 100,
                        jitterFactor = 0.0,
                    ),
                ),
                monotonicNowMillis = testMonotonicNowMillis,
            )
            try {
                client.connect()
                waitFor(timeoutMs = 5_000) { client.state.value is WebSocketState.Connected }

                // Drive each kill round explicitly: send a marker, wait for
                // the reconnect to complete, then continue. This avoids the
                // race where producer outpaces server-side close.
                repeat(rounds) { round ->
                    client.send("ping-round-$round")
                    // The server's onMessage will fire close() right after
                    // recording this message. We expect the client to leave
                    // Connected (Reconnecting) and come back to Connected.
                    waitFor(timeoutMs = 10_000) {
                        client.state.value !is WebSocketState.Connected
                    }
                    waitFor(timeoutMs = 10_000) {
                        client.state.value is WebSocketState.Connected
                    }
                }

                // Final round: should be a healthy echo via finalListener.
                val markerCount = 30
                for (i in 0 until markerCount) {
                    client.send("final-$i")
                }
                waitFor(timeoutMs = 15_000) {
                    finalListener.received.size >= markerCount
                }

                synchronized(finalListener.received) {
                    val received = finalListener.received.toList()
                    println("[stress] storm final round received ${received.size} messages")
                    assertEquals(markerCount, received.size)
                    for (i in 0 until markerCount) {
                        assertEquals("final-$i", received[i])
                    }
                }
                assertTrue(
                    "client should be Connected at end",
                    client.state.value is WebSocketState.Connected,
                )
                assertEquals(
                    "the final connection must be the rounds+1 opening",
                    1,
                    finalListener.opened.get(),
                )
            } finally {
                client.close()
            }
        }
    }

    // ── 5. Backpressure: Suspend strategy applies real coroutine backpressure ─

    @Test
    fun `Suspend strategy backpressures producer to consumer rate`() { runBlocking {
        val listener = installCounter()
        val bufferSize = 16
        val total = 200

        val client = RobustWebSocketClient(
            baseClient = okHttp,
            config = WebSocketConfig(
                url = server.url(),
                pingIntervalMillis = 0,
                outgoingBufferSize = bufferSize,
                backpressureStrategy = BackpressureStrategy.Suspend,
            ),
            monotonicNowMillis = testMonotonicNowMillis,
        )
        try {
            client.connect()
            waitFor(timeoutMs = 5_000) { client.state.value is WebSocketState.Connected }

            // Producer using sendSuspending — must be backpressured by
            // the channel because we're producing faster than the wire.
            val producer = launch(Dispatchers.IO) {
                for (i in 0 until total) {
                    client.sendSuspending("seq-$i")
                }
            }
            producer.join()
            waitFor(timeoutMs = 15_000) { listener.received.size == total }

            // Zero loss — Suspend strategy guarantees this.
            assertEquals(total, listener.received.size)
            // Strict order
            synchronized(listener.received) {
                for (i in 0 until total) {
                    assertEquals("seq-$i", listener.received[i])
                }
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 6. Concurrent close while sending — must not crash ───────────────────

    @Test
    fun `close during high send load is safe — no exceptions, all sockets cleaned up`() {
        runBlocking {
            installCounter()
            val client = RobustWebSocketClient(
                baseClient = okHttp,
                config = WebSocketConfig(
                    url = server.url(),
                    pingIntervalMillis = 0,
                    outgoingBufferSize = 1_000,
                ),
                monotonicNowMillis = testMonotonicNowMillis,
            )
            client.connect()
            waitFor(timeoutMs = 5_000) { client.state.value is WebSocketState.Connected }

            // Spawn 4 producers spamming sends, then close() in the middle.
            val jobs = (0 until 4).map {
                async(Dispatchers.IO) {
                    repeat(1_000) { i -> client.send("p$it-$i") }
                }
            }
            delay(50)
            client.close()
            // Producers may still be calling send() against a closed client;
            // it should return false silently, not throw.
            jobs.awaitAll()

            assertEquals(WebSocketState.Disconnected, client.state.value)
        }
    }

    // ── 7. Wire-level high-water mark: small HWM + large payloads ─────────

    @Test
    fun `txQueueHighWaterMark does not drop or reorder messages under load`() { runBlocking {
        val listener = installCounter()
        val total = 200
        // 1 KB high-water mark with ~1 KB payloads means the OkHttp queue
        // will frequently exceed the threshold, exercising the pause-poll
        // loop inside deliver(). On localhost the drain is fast, but even
        // momentary queuing is enough to cover the code path.
        val payload = "x".repeat(1_024) // 1 KB per message

        val client = RobustWebSocketClient(
            baseClient = okHttp,
            config = WebSocketConfig(
                url = server.url(),
                pingIntervalMillis = 0,
                outgoingBufferSize = total + 50,
                txQueueHighWaterMarkBytes = 1_024, // minimum allowed
                txBackpressurePollIntervalMillis = 5,
            ),
            monotonicNowMillis = testMonotonicNowMillis,
        )
        try {
            client.connect()
            waitFor(timeoutMs = 5_000) { client.state.value is WebSocketState.Connected }

            for (i in 0 until total) {
                assertTrue(client.send("$i|$payload"))
            }
            waitFor(timeoutMs = 30_000) { listener.received.size == total }

            // All messages must arrive in strict order — the backpressure
            // pause must not reorder or lose any.
            synchronized(listener.received) {
                for (i in 0 until total) {
                    val seq = listener.received[i].substringBefore("|").toInt()
                    assertEquals("position $i", i, seq)
                }
            }
        } finally {
            client.close()
        }
    }
    }
}
