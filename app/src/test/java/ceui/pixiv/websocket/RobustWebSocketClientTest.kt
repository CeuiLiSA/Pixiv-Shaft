package ceui.pixiv.websocket

import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun jvmMonotonicMillis(): Long = System.nanoTime() / 1_000_000L

/**
 * Integration tests for [RobustWebSocketClient] driven by a real
 * [TestWebSocketServer] (MockWebServer with `withWebSocketUpgrade`).
 *
 * These tests use real I/O and real time; they avoid `runTest` because the
 * MockWebServer side runs on real OkHttp dispatcher threads. To stay fast
 * and deterministic, every connection has its own port and the timeouts
 * are bounded with [withTimeout].
 */
class RobustWebSocketClientTest {

    private lateinit var server: TestWebSocketServer
    private lateinit var okHttp: OkHttpClient

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

    private fun newClient(
        url: String = server.url(),
        outgoingBufferSize: Int = 64,
        backpressureStrategy: BackpressureStrategy = BackpressureStrategy.DropOldest,
        reconnectStrategy: ReconnectStrategy = ExponentialBackoffWithJitter(
            initialDelayMillis = 50,
            maxDelayMillis = 200,
            jitterFactor = 0.0,
        ),
        authProvider: WebSocketAuthProvider? = null,
        maxAuthRefreshAttempts: Int = 1,
        connectivityObserver: ConnectivityObserver? = null,
        monotonicNowMillis: () -> Long = ::jvmMonotonicMillis,
    ) = RobustWebSocketClient(
        baseClient = okHttp,
        config = WebSocketConfig(
            url = url,
            pingIntervalMillis = 0, // disable for tests — we drive failures manually
            outgoingBufferSize = outgoingBufferSize,
            backpressureStrategy = backpressureStrategy,
            reconnectStrategy = reconnectStrategy,
            maxAuthRefreshAttempts = maxAuthRefreshAttempts,
        ),
        authProvider = authProvider,
        connectivityObserver = connectivityObserver,
        monotonicNowMillis = monotonicNowMillis,
    )

    /**
     * Scriptable [WebSocketAuthProvider] for tests. Pre-loaded with a list of
     * tokens; each [onAuthFailure] consumes one. Records every call.
     */
    private class FakeAuthProvider(
        initialTokens: List<String>,
    ) : WebSocketAuthProvider {
        private val tokens: ArrayDeque<String> = ArrayDeque(initialTokens)
        val headerCalls: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val refreshCalls: MutableList<Map<String, String>> =
            Collections.synchronizedList(mutableListOf())

        override fun headers(): Map<String, String> {
            val current = tokens.firstOrNull()
            headerCalls.add(current)
            return if (current == null) emptyMap() else mapOf("Authorization" to "Bearer $current")
        }

        override fun onAuthFailure(failedHeaders: Map<String, String>): Boolean {
            refreshCalls.add(failedHeaders)
            // Consume the token that just failed.
            if (tokens.isNotEmpty()) tokens.removeFirst()
            // Refresh succeeds iff we still have a fresh token to hand out.
            return tokens.isNotEmpty()
        }
    }

    /**
     * Polls [state] value with a short delay until [predicate] matches or
     * the timeout fires. We use polling instead of `state.first { ... }`
     * because mixing kotlinx.coroutines flow primitives with the threads
     * MockWebServer dispatches WebSocket callbacks on has shown subtle
     * delivery delays in this test setup — direct polling is dramatically
     * more reliable here and the cost (a few `delay(10)` cycles) is trivial.
     */
    private suspend fun RobustWebSocketClient.awaitState(
        timeoutMs: Long = 5_000,
        predicate: (WebSocketState) -> Boolean,
    ): WebSocketState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val current = state.value
            if (predicate(current)) return current
            if (System.currentTimeMillis() > deadline) {
                error("awaitState timed out after ${timeoutMs}ms; current state = $current")
            }
            delay(10)
        }
    }

    private suspend fun RobustWebSocketClient.awaitConnected() =
        awaitState { it is WebSocketState.Connected }

    private suspend fun RobustWebSocketClient.awaitDisconnected() =
        awaitState { it is WebSocketState.Disconnected }

    private suspend fun RobustWebSocketClient.awaitReconnecting() =
        awaitState { it is WebSocketState.Reconnecting }

    /**
     * Subscribes to both of [client]'s observable flows — `events` (lifecycle)
     * and `incoming` (business messages) — and records every emission into
     * thread-safe lists. The recorder must be started **before** the action
     * that produces the event under test, since the source SharedFlows have
     * `replay = 0` (production semantics) — late subscribers do not see
     * past events.
     */
    private class EventRecorder(scope: CoroutineScope, client: RobustWebSocketClient) {
        val events: MutableList<WebSocketEvent> = Collections.synchronizedList(mutableListOf())
        val incoming: MutableList<IncomingMessage> = Collections.synchronizedList(mutableListOf())

        private val eventJob: Job = scope.launch {
            client.events.collect { events.add(it) }
        }
        private val incomingJob: Job = scope.launch {
            client.incoming.collect { incoming.add(it) }
        }

        suspend fun await(
            timeoutMs: Long = 5_000,
            predicate: (WebSocketEvent) -> Boolean,
        ): WebSocketEvent {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val match = synchronized(events) { events.firstOrNull(predicate) }
                if (match != null) return match
                if (System.currentTimeMillis() > deadline) {
                    val seen = synchronized(events) { events.toList() }
                    error("EventRecorder timed out after ${timeoutMs}ms; seen events: $seen")
                }
                delay(10)
            }
        }

        suspend fun awaitIncoming(
            timeoutMs: Long = 5_000,
            predicate: (IncomingMessage) -> Boolean,
        ): IncomingMessage {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val match = synchronized(incoming) { incoming.firstOrNull(predicate) }
                if (match != null) return match
                if (System.currentTimeMillis() > deadline) {
                    val seen = synchronized(incoming) { incoming.toList() }
                    error("EventRecorder timed out after ${timeoutMs}ms; seen incoming: $seen")
                }
                delay(10)
            }
        }

        fun stop() {
            eventJob.cancel()
            incomingJob.cancel()
        }
    }

    private suspend inline fun CoroutineScope.recordEvents(
        client: RobustWebSocketClient,
        block: (EventRecorder) -> Unit,
    ) {
        val recorder = EventRecorder(this, client)
        // Tiny delay so the launched collectors are actually subscribed
        // before the caller does anything. SharedFlow.collect registers
        // synchronously inside the launched coroutine — we just need the
        // event loop to schedule it once.
        delay(20)
        try {
            block(recorder)
        } finally {
            recorder.stop()
        }
    }

    // ── 1. Happy path ─────────────────────────────────────────────────────────

    @Test
    fun `connect, send, receive echo, disconnect`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            recordEvents(client) { recorder ->
                client.connect()
                client.awaitConnected()

                assertTrue(client.send("hello"))

                // Server records the inbound text
                withTimeout(5.seconds) {
                    val ev = server.serverEvents.receive()
                    assertTrue("expected Open, got $ev", ev is TestWebSocketServer.ServerEvent.Open)
                    val txt = server.serverEvents.receive()
                    assertEquals(TestWebSocketServer.ServerEvent.Text("hello"), txt)
                }

                // Client receives the echo (via the EventRecorder which was
                // subscribed BEFORE the send, so the replay=0 SharedFlow
                // doesn't drop the message under us).
                val msg = recorder.awaitIncoming {
                    it is IncomingMessage.Text && it.text == "hello"
                }
                assertEquals("hello", (msg as IncomingMessage.Text).text)
            }

            client.disconnect()
            client.awaitDisconnected()
        } finally {
            client.close()
        }
    }
    }

    // ── 2. State sequence ─────────────────────────────────────────────────────

    @Test
    fun `state sequence Idle to Connecting to Connected to Disconnected`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            assertEquals(WebSocketState.Idle, client.state.value)
            client.connect()

            // Wait for transitions; we don't care about exact timing of
            // Connecting (it may collapse if onOpen fires before we observe).
            client.awaitConnected()
            assertTrue(client.state.value is WebSocketState.Connected)

            client.disconnect()
            client.awaitDisconnected()
            assertEquals(WebSocketState.Disconnected, client.state.value)
        } finally {
            client.close()
        }
    }
    }

    @Test
    fun `connection timestamp comes from injected uptime clock`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient(monotonicNowMillis = { 12_345L })
        try {
            client.connect()
            client.awaitConnected()

            assertEquals(WebSocketState.Connected(sinceMillis = 12_345L), client.state.value)
        } finally {
            client.close()
        }
    }
    }

    @Test
    fun `reconnect deadline uses the same injected uptime clock`() { runBlocking {
        server.enqueueRejection(code = 503)
        val client = newClient(
            reconnectStrategy = ReconnectStrategy { _, _ -> 5_000L },
            monotonicNowMillis = { 12_345L },
        )
        try {
            client.connect()
            val reconnecting = withTimeout(5.seconds) {
                client.state.first { it is WebSocketState.Reconnecting }
            } as WebSocketState.Reconnecting

            assertEquals(5_000L, reconnecting.delayMillis)
            assertEquals(17_345L, reconnecting.nextAttemptAtMillis)
        } finally {
            client.close()
        }
    }
    }

    // ── 3. Idempotent connect ─────────────────────────────────────────────────

    @Test
    fun `connect is idempotent — second call while Connected does nothing`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            client.connect()
            client.awaitConnected()
            val firstStamp = (client.state.value as WebSocketState.Connected).sinceMillis

            // Second call must NOT enqueue another upgrade — server has only
            // one queued, second upgrade attempt would hang/fail.
            client.connect()
            client.connect()
            client.connect()
            delay(100) // give it a chance to misbehave

            assertTrue(client.state.value is WebSocketState.Connected)
            assertEquals(
                "second connect() must not reset the connection",
                firstStamp,
                (client.state.value as WebSocketState.Connected).sinceMillis,
            )
        } finally {
            client.close()
        }
    }
    }

    // ── 4. disconnect blocks reconnect ────────────────────────────────────────

    @Test
    fun `disconnect blocks auto-reconnect even if server later closes`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            client.connect()
            client.awaitConnected()

            client.disconnect()
            client.awaitDisconnected()

            // Wait long enough that any (incorrect) backoff retry would have
            // fired. With initial delay 50ms in newClient(), 500ms is plenty.
            delay(500)
            assertEquals(WebSocketState.Disconnected, client.state.value)

            // send() must reject after disconnect
            assertFalse(client.send("nope"))
        } finally {
            client.close()
        }
    }
    }

    // ── 5. Server-initiated close triggers reconnect ──────────────────────────

    @Test
    fun `server close triggers automatic reconnect`() { runBlocking {
        // Two upgrades: first one closes after first message; second is normal echo
        server.enqueueClosingUpgrade(code = 1011, reason = "first dies")
        server.enqueueEchoUpgrade()

        val client = newClient()
        try {
            recordEvents(client) { recorder ->
                client.connect()
                client.awaitConnected()

                // Trigger the server-side close
                client.send("kill me")

                // We should observe Reconnecting then Connected again
                client.awaitReconnecting()
                client.awaitConnected()

                // The new connection works
                client.send("hello-after-reconnect")
                val msg = recorder.awaitIncoming {
                    it is IncomingMessage.Text && it.text == "hello-after-reconnect"
                }
                assertNotNull(msg)
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 6. Stale-socket guard (P0-1 regression test) ──────────────────────────

    @Test
    fun `stale socket close events do not pollute new connection`() { runBlocking {
        // Two upgrades: first will be force-closed mid-life; second is healthy
        server.enqueueEchoUpgrade()
        server.enqueueEchoUpgrade()

        val client = newClient()
        try {
            recordEvents(client) { recorder ->
                client.connect()
                client.awaitConnected()

                // Cancel the underlying socket — listener will fire onFailure
                // asynchronously and the natural reconnect path kicks in. We
                // detect the second connection by waiting for a *second* Open
                // event in the recorder. sinceMillis is a monotonic timestamp,
                // not a connection identity and is not guaranteed to change
                // between two very fast handshakes.
                val openCountBefore = synchronized(recorder.events) {
                    recorder.events.count { it is WebSocketEvent.Open }
                }
                client.cancel()
                recorder.await(timeoutMs = 10_000) {
                    synchronized(recorder.events) {
                        recorder.events.count { e -> e is WebSocketEvent.Open } > openCountBefore
                    }
                }
                client.awaitConnected()

                // Now send something — if the stale onClosed/onFailure from
                // the first socket has polluted state, this won't echo back.
                client.send("after-stale")
                val msg = recorder.awaitIncoming {
                    it is IncomingMessage.Text && it.text == "after-stale"
                }
                assertNotNull(msg)
                assertTrue(
                    "client must remain connected",
                    client.state.value is WebSocketState.Connected,
                )
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 7. Buffered messages flushed in order on (re)connect ──────────────────

    @Test
    fun `messages enqueued before connect are flushed in order on connect`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient(outgoingBufferSize = 64)
        try {
            // Enqueue 5 messages BEFORE calling connect().
            assertTrue(client.send("m1"))
            assertTrue(client.send("m2"))
            assertTrue(client.send("m3"))
            assertTrue(client.send("m4"))
            assertTrue(client.send("m5"))

            client.connect()
            client.awaitConnected()

            // Server should observe Open then m1..m5 in order.
            withTimeout(5.seconds) {
                assertTrue(server.serverEvents.receive() is TestWebSocketServer.ServerEvent.Open)
                val received = (1..5).map {
                    val ev = server.serverEvents.receive()
                    assertTrue("expected Text, got $ev", ev is TestWebSocketServer.ServerEvent.Text)
                    (ev as TestWebSocketServer.ServerEvent.Text).text
                }
                assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), received)
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 8. drain on disconnect ────────────────────────────────────────────────

    @Test
    fun `disconnect drops buffered messages — they do NOT survive a reconnect`() { runBlocking {
        server.enqueueEchoUpgrade()  // for the first connection
        server.enqueueEchoUpgrade()  // for the second connection
        val client = newClient()
        try {
            // Enqueue messages but never let them deliver — disconnect first.
            assertTrue(client.send("ghost1"))
            assertTrue(client.send("ghost2"))
            client.disconnect()
            client.awaitDisconnected()

            // Now reconnect; ghost messages must NOT show up.
            client.connect()
            client.awaitConnected()

            // Send a known marker so we can wait for it on the server side.
            client.send("marker")
            withTimeout(5.seconds) {
                // Drain server events looking for "marker", asserting we
                // never see "ghost1" or "ghost2".
                val seen = mutableListOf<String>()
                while (true) {
                    val ev = server.serverEvents.receive()
                    if (ev is TestWebSocketServer.ServerEvent.Text) {
                        seen += ev.text
                        if (ev.text == "marker") break
                    }
                }
                assertFalse("ghost1 leaked", "ghost1" in seen)
                assertFalse("ghost2 leaked", "ghost2" in seen)
                assertEquals(listOf("marker"), seen)
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 9. sendSuspending suspends on full buffer with Suspend strategy ───────

    @Test
    fun `sendSuspending suspends when buffer is full under Suspend strategy`() { runBlocking {
        // No server enqueued — client will sit in Connecting/Reconnecting
        // forever, draining nothing. The buffer fills, then the next
        // sendSuspending must actually suspend.
        val client = newClient(
            outgoingBufferSize = 2,
            backpressureStrategy = BackpressureStrategy.Suspend,
        )
        try {
            // Don't connect — keep the consumer parked on state.first.
            client.sendSuspending("a")
            client.sendSuspending("b")
            // Channel(capacity=2) holds 2 + 1 in transit possibly; do a
            // generous fill before assuming the next call suspends.
            client.sendSuspending("c") // may or may not suspend depending on rendezvous

            // The next one MUST suspend; we wrap in async + verify it doesn't
            // complete within a timeout.
            val deferred = async(Dispatchers.IO) { client.sendSuspending("d") }
            delay(200)
            assertFalse("sendSuspending must suspend on full buffer", deferred.isCompleted)
            deferred.cancel()
        } finally {
            client.close()
        }
    }
    }

    // ── 10. DropOldest backpressure ───────────────────────────────────────────

    @Test
    fun `DropOldest discards oldest when buffer overflows`() { runBlocking {
        // The DropOldest invariant is decided **synchronously** by
        // [Channel.trySend] before any wire I/O happens. Real OkHttp +
        // MockWebServer adds nothing here except wall-clock noise — so we
        // wire the client to a [FakeWebSocketFactory] that synthesises
        // `onOpen` and records sent frames in memory. Pure event-driven
        // assertion, no `withTimeout` cliff.
        val fakeFactory = FakeWebSocketFactory()
        val client = RobustWebSocketClient(
            baseClient = okHttp,
            config = WebSocketConfig(
                url = server.url(),
                pingIntervalMillis = 0,
                outgoingBufferSize = 4,
                backpressureStrategy = BackpressureStrategy.DropOldest,
                reconnectStrategy = ExponentialBackoffWithJitter(
                    initialDelayMillis = 50,
                    maxDelayMillis = 200,
                    jitterFactor = 0.0,
                ),
            ),
            monotonicNowMillis = ::jvmMonotonicMillis,
            webSocketFactory = fakeFactory.asFactory(),
        )
        try {
            // Don't connect — fill buffer up past capacity. The consumer
            // coroutine is launched on Dispatchers.IO at construction. *If*
            // it is already parked in receive() when the sends start, the
            // very FIRST send is handed off as a rendezvous and held inside
            // the consumer's deliver() loop (blocked waiting for Connected);
            // if the IO thread hasn't scheduled it yet, m1 is simply the
            // first message evicted. Either way the next
            // [outgoingBufferSize] sends fill the channel buffer and later
            // sends drop the oldest *buffered* item.
            //
            // So the 10 sends end up surviving as:
            //   - m1      : optional, in transit inside the consumer
            //   - m7..m10 : latest 4 in the channel buffer (m2..m6 dropped)
            // Asserting exactly 5 was a bet on dispatcher scheduling that
            // lost on a loaded machine.
            for (i in 1..10) assertTrue(client.send("m$i"))

            client.connect()
            client.awaitConnected()

            // The fake records each successful send into [sentText]. Poll
            // until the 4 buffered survivors have landed — no
            // TCP/handshake/echo, so this lands in tens of ms even on a
            // heavily-loaded CI runner — then give the optional pre-fetched
            // m1 (always delivered first) a settle window.
            val fake = fakeFactory.sockets.single()
            awaitFakeSends(fake, expected = 4)
            delay(100)

            val sent = fake.sentText.toList()
            val expectedTail = listOf("m7", "m8", "m9", "m10")
            assertTrue(
                "got $sent",
                sent == expectedTail || sent == listOf("m1") + expectedTail,
            )
        } finally {
            client.close()
            fakeFactory.close()
        }
    }
    }

    /**
     * Poll [FakeWebSocket.sentText] until it reaches [expected] entries or
     * [timeoutMs] elapses. Equivalent to `awaitState`-style polling but for
     * the fake socket's recorded sends.
     */
    private suspend fun awaitFakeSends(
        socket: FakeWebSocket,
        expected: Int,
        timeoutMs: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (socket.sentText.size < expected) {
            if (System.currentTimeMillis() > deadline) {
                error(
                    "fake socket received only ${socket.sentText.size}/$expected sends " +
                        "after ${timeoutMs}ms; got=${socket.sentText.toList()}",
                )
            }
            delay(10)
        }
    }

    // ── 11. DropNewest backpressure ───────────────────────────────────────────

    @Test
    fun `DropNewest rejects new sends when buffer is full`() { runBlocking {
        val client = newClient(
            outgoingBufferSize = 3,
            backpressureStrategy = BackpressureStrategy.DropNewest,
        )
        try {
            // Don't connect — buffer fills, then new sends fail.
            assertTrue(client.send("a"))
            assertTrue(client.send("b"))
            assertTrue(client.send("c"))
            // Channel may keep one in transit; the next SHOULD eventually fail.
            // Loop a few times to drain any rendezvous slack.
            var rejectedAt = -1
            for (i in 0 until 10) {
                if (!client.send("x$i")) { rejectedAt = i; break }
            }
            assertTrue(
                "DropNewest must eventually reject sends; got rejection at $rejectedAt",
                rejectedAt >= 0,
            )
        } finally {
            client.close()
        }
    }
    }

    // ── 12. Terminate after maxAttempts ───────────────────────────────────────

    @Test
    fun `client gives up and goes to Disconnected after maxAttempts`() { runBlocking {
        // Server only accepts the first upgrade then refuses everything; we
        // configure a strategy with maxAttempts=2 so we burn through fast.
        server.enqueueRejection()
        server.enqueueRejection()
        server.enqueueRejection()

        val client = RobustWebSocketClient(
            baseClient = okHttp,
            config = WebSocketConfig(
                url = server.url(),
                pingIntervalMillis = 0,
                reconnectStrategy = ExponentialBackoffWithJitter(
                    initialDelayMillis = 20,
                    maxDelayMillis = 50,
                    maxAttempts = 2,
                    jitterFactor = 0.0,
                ),
            ),
            monotonicNowMillis = ::jvmMonotonicMillis,
        )
        try {
            client.connect()
            // Wait for the strategy to give up.
            withTimeout(10.seconds) {
                client.state.first { it is WebSocketState.Disconnected }
            }
            assertEquals(WebSocketState.Disconnected, client.state.value)
        } finally {
            client.close()
        }
    }
    }

    // ── 13. close() makes everything no-op ────────────────────────────────────

    @Test
    fun `close — subsequent operations are no-op`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()

        client.connect()
        client.awaitConnected()
        client.close()

        // Every public method must be safe and a no-op.
        assertFalse(client.send("after-close"))
        client.connect() // no-op
        client.disconnect() // no-op
        client.cancel() // no-op
        client.close() // idempotent

        assertEquals(WebSocketState.Disconnected, client.state.value)
    }
    }

    // ── 14. send() return values across lifecycle ─────────────────────────────

    @Test
    fun `send return values reflect lifecycle`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            // Idle: send buffers, returns true
            assertTrue(client.send("idle"))

            client.connect()
            client.awaitConnected()
            assertTrue(client.send("connected"))

            client.disconnect()
            client.awaitDisconnected()
            assertFalse("send() after disconnect must return false", client.send("disconnected"))

            // Reconnect → send again
            server.enqueueEchoUpgrade()
            client.connect()
            client.awaitConnected()
            assertTrue(client.send("reconnected"))
        } finally {
            client.close()
        }
    }
    }

    // ── 15. Empty / null behaviors ────────────────────────────────────────────

    @Test
    fun `empty text frame is delivered as-is`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            recordEvents(client) { recorder ->
                client.connect()
                client.awaitConnected()
                assertTrue(client.send(""))
                val msg = recorder.awaitIncoming { it is IncomingMessage.Text }
                assertEquals("", (msg as IncomingMessage.Text).text)
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 16. cancel() after disconnect is a no-op ──────────────────────────────

    @Test
    fun `cancel after disconnect is a no-op`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            client.connect()
            client.awaitConnected()
            client.disconnect()
            client.awaitDisconnected()
            // Should not throw, should not change state
            client.cancel()
            client.cancel()
            assertEquals(WebSocketState.Disconnected, client.state.value)
        } finally {
            client.close()
        }
    }
    }

    // ── 17. P1 fix: disconnect → connect cycle drops queued messages ──────────

    @Test
    fun `disconnect then connect drops messages enqueued during disconnect`() { runBlocking {
        // Same intent as test #8 but specifically validates the new
        // "consumer drop-drains via stopRequested" behaviour: with the old
        // synchronous-drain implementation, a sufficiently fast send right
        // after disconnect could slip past drainOutboxLocked. With the new
        // implementation, the consumer is alive throughout disconnect and
        // discards every message it pulls off the channel as long as
        // stopRequested is true.
        server.enqueueEchoUpgrade() // first connection
        server.enqueueEchoUpgrade() // second connection
        val client = newClient()
        try {
            client.connect()
            client.awaitConnected()
            // Skip the server-side Open event from the first connection.
            withTimeout(5.seconds) { server.serverEvents.receive() }

            client.disconnect()
            client.awaitDisconnected()

            // Try to send while stopped — must be rejected.
            for (i in 0 until 50) {
                assertFalse("ghost-$i must be rejected post-disconnect", client.send("ghost-$i"))
            }

            // Reconnect and send a unique marker so we can drain the server
            // events looking for any leaked ghost.
            client.connect()
            client.awaitConnected()
            client.send("marker")

            withTimeout(5.seconds) {
                val seen = mutableListOf<String>()
                while (true) {
                    val ev = server.serverEvents.receive()
                    if (ev is TestWebSocketServer.ServerEvent.Text) {
                        seen += ev.text
                        if (ev.text == "marker") break
                    }
                }
                assertEquals(listOf("marker"), seen)
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 18. P0-1: Auth refresh — happy path ───────────────────────────────────

    @Test
    fun `401 triggers auth refresh and reconnect succeeds with new token`() { runBlocking {
        // First upgrade fails with 401; second is the healthy echo.
        server.enqueueRejection(code = 401, body = "expired")
        server.enqueueEchoUpgrade()

        val authProvider = FakeAuthProvider(initialTokens = listOf("stale", "fresh"))
        val client = newClient(authProvider = authProvider)
        try {
            client.connect()
            client.awaitConnected()

            // headers() called twice: once for the first (failing) attempt,
            // once for the post-refresh reconnect.
            assertEquals(listOf("stale", "fresh"), authProvider.headerCalls.toList())
            // onAuthFailure called exactly once with the failing headers.
            assertEquals(1, authProvider.refreshCalls.size)
            assertEquals(
                "Bearer stale",
                authProvider.refreshCalls[0]["Authorization"],
            )
        } finally {
            client.close()
        }
    }
    }

    // ── 19. P0-1: Auth refresh — exhausted ────────────────────────────────────

    @Test
    fun `consecutive auth failures terminate after maxAuthRefreshAttempts`() { runBlocking {
        // Two 401s in a row. With maxAuthRefreshAttempts = 1, the first 401
        // triggers a refresh, the second 401 must terminate immediately.
        server.enqueueRejection(code = 401, body = "expired")
        server.enqueueRejection(code = 401, body = "still expired")

        val authProvider = FakeAuthProvider(initialTokens = listOf("stale", "also-stale"))
        val client = newClient(
            authProvider = authProvider,
            maxAuthRefreshAttempts = 1,
        )
        try {
            client.connect()
            client.awaitDisconnected()
            assertEquals(WebSocketState.Disconnected, client.state.value)
            // Two header calls (two upgrade attempts), one refresh between them.
            assertEquals(listOf("stale", "also-stale"), authProvider.headerCalls.toList())
            assertEquals(1, authProvider.refreshCalls.size)
        } finally {
            client.close()
        }
    }
    }

    // ── 20. P0-1: Auth refresh — refresh hook returns false ───────────────────

    @Test
    fun `auth refresh returning false terminates immediately`() { runBlocking {
        // 401 first; refresh hook will return false (no more tokens to hand
        // out), so client should give up without attempting a second connect.
        server.enqueueRejection(code = 401, body = "expired")

        // Provider has only one token, so the refresh consumes it and the
        // next refresh returns false.
        val authProvider = FakeAuthProvider(initialTokens = listOf("only-token"))
        val client = newClient(
            authProvider = authProvider,
            // Use a high budget to prove that termination is driven by the
            // false refresh result, not by the budget.
            maxAuthRefreshAttempts = 5,
        )
        try {
            client.connect()
            client.awaitDisconnected()
            assertEquals(WebSocketState.Disconnected, client.state.value)
            assertEquals(1, authProvider.refreshCalls.size)
            // Only one header call — the second connect was never attempted
            // because the refresh returned false.
            assertEquals(listOf("only-token"), authProvider.headerCalls.toList())
        } finally {
            client.close()
        }
    }
    }

    // ── 21. P0-1: Auth refresh budget refills on successful connect ───────────

    @Test
    fun `successful connect resets the auth refresh budget`() { runBlocking {
        // Sequence: 401 → refresh → success → server closes → reconnect
        // 401 → refresh → success. The second 401 burst should be allowed
        // because the successful connect between bursts reset the counter.
        server.enqueueRejection(code = 401, body = "expired #1")
        server.enqueueClosingUpgrade(code = 1011, reason = "kick")
        server.enqueueRejection(code = 401, body = "expired #2")
        server.enqueueEchoUpgrade()

        val authProvider = FakeAuthProvider(
            initialTokens = listOf("t1", "t2", "t3", "t4"),
        )
        val client = newClient(
            authProvider = authProvider,
            maxAuthRefreshAttempts = 1, // budget is 1, but should refill
        )
        try {
            client.connect()
            client.awaitConnected() // first successful connect (with t2)

            // Trigger the server-side close. After the close, the client
            // will reconnect → 401 → refresh → reconnect → success. We
            // can't `awaitConnected()` here because state is *already*
            // Connected from the first cycle and would return immediately.
            // Instead poll the auth provider's call counts: when we've seen
            // 4 header calls and 2 refreshes, the full sequence has run.
            client.send("kill me")
            withTimeout(15.seconds) {
                while (authProvider.headerCalls.size < 4) delay(20)
            }
            // The 4th header call happens *during* launchConnectLocked, just
            // before the OkHttp upgrade fires — give it a beat to actually
            // complete the handshake.
            client.awaitConnected()

            // t1: initial connect (401) → refresh consumes t1
            // t2: post-refresh reconnect (success, closes with 1011)
            // t2: normal reconnect after server close (same token, no auth failure consumed it) → 401 → refresh consumes t2
            // t3: post-refresh reconnect (success)
            assertEquals(listOf("t1", "t2", "t2", "t3"), authProvider.headerCalls.toList())
            assertEquals(2, authProvider.refreshCalls.size)
        } finally {
            client.close()
        }
    }
    }

    // ── 22. P0-1: maxAuthRefreshAttempts = 0 means no refresh, fail fast ──────

    @Test
    fun `maxAuthRefreshAttempts zero means fail-fast on first 401`() { runBlocking {
        server.enqueueRejection(code = 401, body = "expired")

        val authProvider = FakeAuthProvider(initialTokens = listOf("t1", "t2"))
        val client = newClient(
            authProvider = authProvider,
            maxAuthRefreshAttempts = 0,
        )
        try {
            client.connect()
            client.awaitDisconnected()
            // Refresh was never called.
            assertEquals(0, authProvider.refreshCalls.size)
            // Only the initial header call happened.
            assertEquals(listOf("t1"), authProvider.headerCalls.toList())
        } finally {
            client.close()
        }
    }
    }

    // ── 23. P0-1: non-auth failures still go through reconnect strategy ───────

    @Test
    fun `non-auth failures with authProvider still go through reconnect strategy`() { runBlocking {
        // Server rejects with 503 (transient, NOT an auth failure). Even
        // with an authProvider configured, the client should follow the
        // normal backoff strategy and not call onAuthFailure.
        server.enqueueRejection(code = 503, body = "down")
        server.enqueueEchoUpgrade()

        val authProvider = FakeAuthProvider(initialTokens = listOf("t1"))
        val client = newClient(authProvider = authProvider)
        try {
            client.connect()
            client.awaitConnected()
            // No refresh calls — 503 isn't an auth failure.
            assertEquals(0, authProvider.refreshCalls.size)
            // Two header calls — initial + reconnect (both used the same token).
            assertEquals(listOf("t1", "t1"), authProvider.headerCalls.toList())
        } finally {
            client.close()
        }
    }
    }

    // ── 24. P0-1: custom isAuthFailure recognises a 4xxx close code ───────────

    @Test
    fun `custom isAuthFailure can treat a close code as auth failure`() { runBlocking {
        // Server accepts the upgrade but immediately closes with code 4401.
        // Our custom provider treats 4401 as auth failure → refresh →
        // reconnect to the second healthy upgrade.
        server.enqueueClosingUpgrade(code = 4401, reason = "auth")
        server.enqueueEchoUpgrade()

        val customProvider = object : WebSocketAuthProvider {
            val refreshCalls = java.util.concurrent.atomic.AtomicInteger(0)
            override fun headers(): Map<String, String> = mapOf("Authorization" to "Bearer xxx")
            override fun isAuthFailure(failure: FailureContext): Boolean =
                failure is FailureContext.Closed && failure.code == 4401
            override fun onAuthFailure(failedHeaders: Map<String, String>): Boolean {
                refreshCalls.incrementAndGet()
                return true
            }
        }

        val client = newClient(authProvider = customProvider)
        try {
            client.connect()
            client.awaitConnected() // initial open
            // Trigger the server-side close.
            client.send("trigger")
            // Wait for the second healthy connect after the refresh.
            client.awaitState(timeoutMs = 10_000) { it is WebSocketState.Connected }
            // ... it might already be Connected if we're fast; check that
            // refresh was actually called.
            withTimeout(5.seconds) {
                while (customProvider.refreshCalls.get() == 0) delay(10)
            }
            assertEquals(1, customProvider.refreshCalls.get())
        } finally {
            client.close()
        }
    }
    }

    // ── 25. Binary frame end-to-end ─────────────────────────────────────────

    @Test
    fun `binary frame send, echo, and receive`() { runBlocking {
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            recordEvents(client) { recorder ->
                client.connect()
                client.awaitConnected()

                val payload = "binary-payload".encodeUtf8()
                assertTrue(client.send(payload))

                // Server records the inbound binary
                withTimeout(5.seconds) {
                    val open = server.serverEvents.receive()
                    assertTrue("expected Open, got $open", open is TestWebSocketServer.ServerEvent.Open)
                    val bin = server.serverEvents.receive()
                    assertTrue("expected Binary, got $bin", bin is TestWebSocketServer.ServerEvent.Binary)
                    assertEquals(payload, (bin as TestWebSocketServer.ServerEvent.Binary).bytes)
                }

                // Client receives the echoed binary
                val msg = recorder.awaitIncoming { it is IncomingMessage.Binary }
                assertEquals(payload, (msg as IncomingMessage.Binary).bytes)
            }

            client.disconnect()
            client.awaitDisconnected()
        } finally {
            client.close()
        }
    }
    }

    // ── 26b. disconnect from Idle is a silent no-op ────────────────────────

    @Test
    fun `disconnect from Idle state is a silent no-op`() { runBlocking {
        // Never call connect() — a freshly-constructed client is in Idle.
        // disconnect() from here must not throw, must not create a socket,
        // and must leave the client in a state where close() still works.
        val client = newClient()
        try {
            assertEquals(WebSocketState.Idle, client.state.value)

            // Must not throw.
            client.disconnect()

            // The KDoc says disconnect always transitions to Disconnected.
            // Verify that and that no HTTP request was ever made.
            assertEquals(WebSocketState.Disconnected, client.state.value)
            assertEquals(
                "disconnect from Idle must not produce any upgrade attempt",
                0, server.server.requestCount,
            )

            // A second disconnect is also a no-op.
            client.disconnect()
            assertEquals(WebSocketState.Disconnected, client.state.value)

            // And the client is still usable — calling connect() after this
            // must succeed (Idle/Disconnected are both valid "stopped" states
            // from which connect() can spin things up).
            server.enqueueEchoUpgrade()
            client.connect()
            client.awaitConnected()
        } finally {
            client.close()
        }
    }
    }

    // ── 27. disconnect while Reconnecting cancels the backoff ──────────────

    @Test
    fun `disconnect while Reconnecting cancels backoff — no further server connections`() { runBlocking {
        // Reject the first upgrade → client enters Reconnecting with a LONG
        // backoff. Then disconnect() while Reconnecting should:
        //   1. cancel the in-flight reconnect coroutine
        //   2. transition to Disconnected
        //   3. make *no further* upgrade attempts for the rest of the test
        server.enqueueRejection(code = 503, body = "down")
        val client = newClient(
            reconnectStrategy = ExponentialBackoffWithJitter(
                initialDelayMillis = 30_000, // long enough that only cancellation can stop it
                maxDelayMillis = 30_000,
                jitterFactor = 0.0,
            ),
        )
        try {
            client.connect()
            client.awaitReconnecting()
            val requestsBeforeDisconnect = server.server.requestCount
            assertEquals(1, requestsBeforeDisconnect)

            client.disconnect()
            client.awaitDisconnected()

            // Give the (now cancelled) 30s backoff plenty of time to
            // accidentally fire if the cancel is broken.
            delay(500)

            assertEquals(
                "disconnect during Reconnecting must cancel the pending connect",
                requestsBeforeDisconnect, server.server.requestCount,
            )
            assertEquals(WebSocketState.Disconnected, client.state.value)
        } finally {
            client.close()
        }
    }
    }

    // ── 28. close while Reconnecting tears down without any late connect ───

    @Test
    fun `close while Reconnecting cancels backoff without making further connections`() { runBlocking {
        // Same shape as test 27 but calls close() — which cancels the whole
        // internal scope, not just the reconnect job. Regression guard
        // against "the reconnect coroutine outlives close() and fires a late
        // newWebSocket".
        server.enqueueRejection(code = 503, body = "down")
        val client = newClient(
            reconnectStrategy = ExponentialBackoffWithJitter(
                initialDelayMillis = 30_000,
                maxDelayMillis = 30_000,
                jitterFactor = 0.0,
            ),
        )
        try {
            client.connect()
            client.awaitReconnecting()
            val requestsBeforeClose = server.server.requestCount
            assertEquals(1, requestsBeforeClose)

            client.close()

            delay(500)

            assertEquals(
                "close during Reconnecting must cancel the pending connect",
                requestsBeforeClose, server.server.requestCount,
            )
            assertEquals(WebSocketState.Disconnected, client.state.value)

            // Post-close invariant: the client is permanently dead.
            // Another connect() is a no-op and must not revive anything.
            client.connect()
            delay(100)
            assertEquals(requestsBeforeClose, server.server.requestCount)
        } finally {
            // Idempotent — close() after close() is documented as safe.
            client.close()
        }
    }
    }

    // ── 29. connect after strategy exhaustion resets and reconnects ────────

    @Test
    fun `connect after strategy gave up resets attempts and reconnects successfully`() { runBlocking {
        // Burn through maxAttempts=2 of rejections to reach Disconnected,
        // then enqueue a healthy upgrade and call connect() again. The
        // attempt counter MUST reset — otherwise the fresh connect would
        // immediately be counted as "attempt 3" and (if someone later adds a
        // guard) could be rejected.
        server.enqueueRejection()
        server.enqueueRejection()
        server.enqueueRejection() // safety — the strategy might poll one extra time

        val client = RobustWebSocketClient(
            baseClient = okHttp,
            config = WebSocketConfig(
                url = server.url(),
                pingIntervalMillis = 0,
                reconnectStrategy = ExponentialBackoffWithJitter(
                    initialDelayMillis = 20,
                    maxDelayMillis = 50,
                    maxAttempts = 2,
                    jitterFactor = 0.0,
                ),
            ),
            monotonicNowMillis = ::jvmMonotonicMillis,
        )
        try {
            client.connect()
            withTimeout(10.seconds) {
                client.state.first { it is WebSocketState.Disconnected }
            }
            assertEquals(WebSocketState.Disconnected, client.state.value)

            // Explicit recovery — user taps "retry".
            server.enqueueEchoUpgrade()
            client.connect()
            client.awaitConnected()

            // And the freshly-connected session is fully functional.
            assertTrue(client.send("after-recovery"))
        } finally {
            client.close()
        }
    }
    }

    // ── 30. Mixed failure sequence: 500 backoff then 401 refresh ───────────

    @Test
    fun `mixed 500 backoff then 401 refresh — both reconnect paths exercised`() { runBlocking {
        // Sequence of upgrades the server will answer with, in order:
        //   1) 503 → non-auth failure → normal backoff reconnect
        //   2) 401 → auth failure    → onAuthFailure refresh + retry
        //   3) echo (success)
        //
        // Asserts two invariants in one run:
        //   - 503 does NOT trigger onAuthFailure (only 401 does)
        //   - a healthy connect after an auth refresh resets the auth budget
        server.enqueueRejection(code = 503, body = "down")
        server.enqueueRejection(code = 401, body = "expired")
        server.enqueueEchoUpgrade()

        val authProvider = FakeAuthProvider(initialTokens = listOf("t1", "t2"))
        val client = newClient(authProvider = authProvider)
        try {
            client.connect()
            client.awaitConnected()

            // headers() fired three times: once per upgrade attempt.
            assertEquals(3, authProvider.headerCalls.size)
            // But onAuthFailure fired exactly once — only the 401 path
            // triggers the refresh contract.
            assertEquals(1, authProvider.refreshCalls.size)
            assertEquals(
                "refresh must have seen the token that failed with 401",
                "Bearer t1", // the token that was in-play when the 401 came
                authProvider.refreshCalls[0]["Authorization"],
            )
        } finally {
            client.close()
        }
    }
    }

    // ── 31. Connectivity "online" edge while Connected is ignored ──────────

    @Test
    fun `connectivity online edge while Connected does not trigger a new connect`() { runBlocking {
        // Fast-retry must ONLY fire from Reconnecting. If a healthy client
        // sees a false→true connectivity transition, nothing should happen.
        // Regression guard against "fast retry always launches connect()".
        server.enqueueEchoUpgrade()
        val connectivity = MutableStateFlow(true)
        val observer = object : ConnectivityObserver {
            override val observeConnectivity: Flow<Boolean> get() = connectivity
        }
        val client = newClient(connectivityObserver = observer)
        try {
            client.connect()
            client.awaitConnected()
            val requestsAfterConnect = server.server.requestCount
            assertEquals(1, requestsAfterConnect)

            // Flip network off → on several times while healthy.
            repeat(3) {
                connectivity.value = false
                delay(20)
                connectivity.value = true
                delay(20)
            }
            // Give any rogue reconnect a chance to fire.
            delay(200)

            assertEquals(
                "fast retry must not run while Connected",
                requestsAfterConnect, server.server.requestCount,
            )
            assertTrue(client.state.value is WebSocketState.Connected)
        } finally {
            client.close()
        }
    }
    }

    // ── 32. Empty binary frame end-to-end ──────────────────────────────────

    @Test
    fun `empty binary frame is delivered end to end`() { runBlocking {
        // Symmetry with the existing "empty text frame" test — OkHttp and
        // our buffering pipeline must both accept a 0-byte binary frame and
        // echo it back unchanged.
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            recordEvents(client) { recorder ->
                client.connect()
                client.awaitConnected()

                val empty = okio.ByteString.EMPTY
                assertTrue(client.send(empty))

                val msg = recorder.awaitIncoming { it is IncomingMessage.Binary }
                val binary = msg as IncomingMessage.Binary
                assertEquals("empty binary must round-trip with 0 bytes", 0, binary.bytes.size)
                assertEquals(empty, binary.bytes)
            }
        } finally {
            client.close()
        }
    }
    }

    // ── 33. Messages enqueued during Reconnecting flush in order ───────────

    @Test
    fun `messages enqueued while Reconnecting flush in order after reconnect`() { runBlocking {
        // First upgrade gets rejected → client enters Reconnecting with a
        // moderate backoff window. During that window, the producer drops
        // several messages into the outbox. After the reconnect lands, all
        // of them must arrive in-order on the server side.
        //
        // This specifically exercises the epoch mechanism: the messages are
        // tagged with currentEpoch at send time, the disconnect caused by
        // the rejection does NOT bump currentEpoch (only explicit
        // disconnect() or coordinator terminate does), so the consumer must
        // NOT drop them as stale.
        server.enqueueRejection(code = 503, body = "down")
        server.enqueueEchoUpgrade()
        val client = newClient(
            reconnectStrategy = ExponentialBackoffWithJitter(
                initialDelayMillis = 300, // long enough to send during the gap
                maxDelayMillis = 300,
                jitterFactor = 0.0,
            ),
        )
        try {
            client.connect()
            client.awaitReconnecting()

            // Enqueue 10 messages in strict order while Reconnecting. All
            // must be accepted (buffer has plenty of room) and survive the
            // reconnect without reordering.
            val sent = (0 until 10).map { "r-$it" }
            for (msg in sent) {
                assertTrue("send during Reconnecting must be accepted: $msg", client.send(msg))
            }

            client.awaitConnected()

            // Drain the server side until we see every sent marker, in order.
            val seen = mutableListOf<String>()
            withTimeout(10.seconds) {
                while (seen.size < sent.size) {
                    val ev = server.serverEvents.receive()
                    if (ev is TestWebSocketServer.ServerEvent.Text && ev.text.startsWith("r-")) {
                        seen += ev.text
                    }
                }
            }
            assertEquals(
                "messages enqueued during Reconnecting must preserve FIFO order on reconnect",
                sent, seen,
            )
        } finally {
            client.close()
        }
    }
    }

    // ── 34. Two independent events subscribers see identical sequences ─────

    @Test
    fun `two independent events subscribers see identical event sequences`() { runBlocking {
        // SharedFlow broadcast semantics — both subscribers must observe the
        // exact same sequence of Open/Closed events for a single
        // connect/disconnect cycle. Regression guard against "a second
        // subscriber accidentally consumed a stream" bugs.
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            val scope = this
            val eventsA: MutableList<WebSocketEvent> =
                Collections.synchronizedList(mutableListOf())
            val eventsB: MutableList<WebSocketEvent> =
                Collections.synchronizedList(mutableListOf())
            val jobA = scope.launch { client.events.collect { eventsA.add(it) } }
            val jobB = scope.launch { client.events.collect { eventsB.add(it) } }
            delay(30) // let both collectors register

            client.connect()
            client.awaitConnected()
            client.disconnect()
            client.awaitDisconnected()

            // Give the DROP_OLDEST SharedFlow a moment to finish delivering
            // the Open + Closed (or Closing) events to both subscribers.
            delay(200)

            jobA.cancel()
            jobB.cancel()

            val snapA = synchronized(eventsA) { eventsA.toList() }
            val snapB = synchronized(eventsB) { eventsB.toList() }

            assertEquals(
                "two independent subscribers must see the same event sequence",
                snapA, snapB,
            )
            assertTrue(
                "sequence must start with an Open event, was $snapA",
                snapA.firstOrNull() is WebSocketEvent.Open,
            )
        } finally {
            client.close()
        }
    }
    }

    // ── 35. Subscribing to incoming AFTER a message arrived does not replay ─

    @Test
    fun `subscribing to incoming after a message arrived does not replay it`() { runBlocking {
        // incoming uses replay = 0 — a subscriber that shows up late must
        // NOT see historical frames. Documented in IncomingMessage's KDoc;
        // tested explicitly here to guard against a well-meaning bump to
        // replay = 1.
        server.enqueueEchoUpgrade()
        val client = newClient()
        try {
            client.connect()
            client.awaitConnected()

            // First subscriber receives the echo.
            val first: MutableList<IncomingMessage> =
                Collections.synchronizedList(mutableListOf())
            val firstJob = launch { client.incoming.collect { first.add(it) } }
            delay(30)
            assertTrue(client.send("historical"))
            withTimeout(5.seconds) {
                while (first.isEmpty()) delay(10)
            }
            firstJob.cancel()
            assertEquals(1, first.size)

            // Second subscriber joins afterwards — must see zero frames.
            val second: MutableList<IncomingMessage> =
                Collections.synchronizedList(mutableListOf())
            val secondJob = launch { client.incoming.collect { second.add(it) } }
            delay(200) // generous window for a rogue replay to arrive
            secondJob.cancel()

            assertEquals(
                "late subscriber must not see the historical frame (replay=0)",
                0, second.size,
            )
        } finally {
            client.close()
        }
    }
    }

    // ── 36. Network-aware fast retry ────────────────────────────────────────

    @Test
    fun `network coming back short-circuits backoff and reconnects immediately`() { runBlocking {
        // First attempt is a 503 rejection that triggers a very long backoff.
        // The second is a healthy echo that we expect only after the fast
        // retry fires — if the backoff ran its course the test would time out.
        server.enqueueRejection(code = 503, body = "down")
        server.enqueueEchoUpgrade()

        val fakeConnectivity = MutableStateFlow(true)
        val connectivityObserver = object : ConnectivityObserver {
            override val observeConnectivity: Flow<Boolean> get() = fakeConnectivity
        }

        val client = newClient(
            reconnectStrategy = ExponentialBackoffWithJitter(
                // Very long backoff — the test would time out if the fast
                // retry didn't cancel it.
                initialDelayMillis = 60_000,
                maxDelayMillis = 60_000,
                jitterFactor = 0.0,
            ),
            connectivityObserver = connectivityObserver,
        )
        try {
            client.connect()

            // The 503 rejection fires onFailure → scheduleReconnect with
            // 60s delay → Reconnecting.
            client.awaitState(timeoutMs = 10_000) { state ->
                state is WebSocketState.Reconnecting && state.delayMillis == 60_000L
            }

            // Flip network off → on to trigger fast retry.
            fakeConnectivity.value = false
            delay(50)
            fakeConnectivity.value = true

            // The fast retry should short-circuit the 60s backoff and
            // connect to the second enqueued upgrade (healthy echo).
            client.awaitConnected()
            assertTrue(client.state.value is WebSocketState.Connected)
        } finally {
            client.close()
        }
    }
    }
}
