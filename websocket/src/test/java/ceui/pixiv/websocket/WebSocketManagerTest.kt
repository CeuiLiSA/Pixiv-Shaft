package ceui.pixiv.websocket

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketManagerTest {

    // ── Test doubles ──────────────────────────────────────────────────────────

    /**
     * Fully scripted [WebSocketClient] stand-in. Records every lifecycle call
     * and lets tests drive the state machine by calling [scriptState].
     */
    private class FakeWebSocketClient(
        val id: Int,
    ) : WebSocketClient {
        private val _state = MutableStateFlow<WebSocketState>(WebSocketState.Idle)
        override val state: MutableStateFlow<WebSocketState> = _state

        private val _events = MutableSharedFlow<WebSocketEvent>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

        private val _incoming = MutableSharedFlow<IncomingMessage>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

        val connectCalls = AtomicInteger(0)
        val disconnectCalls = AtomicInteger(0)
        val cancelCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)
        val sends = CopyOnWriteArrayList<String>()
        val sendBytesSizes = CopyOnWriteArrayList<Int>()

        override fun connect() {
            connectCalls.incrementAndGet()
            _state.value = WebSocketState.Connecting
        }

        override fun disconnect(code: Int, reason: String) {
            disconnectCalls.incrementAndGet()
            _state.value = WebSocketState.Disconnected
        }

        override fun cancel() {
            cancelCalls.incrementAndGet()
        }

        override fun close() {
            closeCalls.incrementAndGet()
            _state.value = WebSocketState.Disconnected
        }

        override fun send(text: String): Boolean {
            sends.add(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean {
            sendBytesSizes.add(bytes.size)
            return true
        }

        override suspend fun sendSuspending(text: String): Boolean {
            sends.add(text)
            return true
        }

        override suspend fun sendSuspending(bytes: ByteString): Boolean {
            sendBytesSizes.add(bytes.size)
            return true
        }

        fun scriptState(new: WebSocketState) {
            _state.value = new
        }

        /**
         * Test-only helper to push an inbound message through [incoming].
         * Mirrors what a real [RobustWebSocketClient] does when its
         * OkHttp listener's `onMessage` fires.
         */
        fun pushIncoming(msg: IncomingMessage) {
            _incoming.tryEmit(msg)
        }
    }

    private fun newConfig(url: String = "wss://example.test/") = WebSocketConfig(url = url)

    /**
     * Factory that hands out a new [FakeWebSocketClient] for each call and
     * records every instance it produced — tests assert against this list.
     */
    private class RecordingFactory {
        val created = CopyOnWriteArrayList<FakeWebSocketClient>()
        private val counter = AtomicInteger(0)
        val factory: (WebSocketConfig) -> WebSocketClient = {
            val c = FakeWebSocketClient(id = counter.incrementAndGet())
            created.add(c)
            c
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `start is idempotent — second call does not duplicate collectors`() = runTest {
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()
        manager.start() // second call — must be a no-op
        manager.start() // third — same

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()

        // If the guard were broken, each of the 3 start() calls would have
        // spun up its own collector and each would have called activate(),
        // producing 3 clients.
        assertEquals("start() must be idempotent", 1, factory.created.size)
        assertEquals(1, factory.created[0].connectCalls.get())
    }

    @Test
    fun `login creates and connects a fresh client`() = runTest {
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        assertNull(manager.activeClient.value)

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()

        assertEquals(1, factory.created.size)
        val client = factory.created[0]
        assertSame(client, manager.activeClient.value)
        assertEquals(1, client.connectCalls.get())
    }

    @Test
    fun `logout closes the active client and clears activeClient`() = runTest {
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val client = factory.created.single()

        loggedIn.tryEmit(false)
        testScheduler.runCurrent()

        assertNull(manager.activeClient.value)
        assertEquals("close() must be called on logout", 1, client.closeCalls.get())
    }

    @Test
    fun `re-login after logout builds a brand new client`() = runTest {
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val first = factory.created.single()

        loggedIn.tryEmit(false)
        testScheduler.runCurrent()

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()

        assertEquals(2, factory.created.size)
        val second = factory.created[1]
        assertNotSame("second login must produce a fresh client", first, second)
        assertSame(second, manager.activeClient.value)
        assertEquals(1, first.closeCalls.get())
        assertEquals(1, second.connectCalls.get())
    }

    @Test
    fun `re-login while old client is Reconnecting replaces it with a fresh one`() = runTest {
        // Regression guard for the activate() fix: a Reconnecting client from
        // a previous session (e.g. trapped on a fatal close code) must NOT be
        // inherited by a fresh login — re-login means "clean slate".
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        // First session
        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val first = factory.created.single()

        // Trap the first client in Reconnecting
        first.scriptState(
            WebSocketState.Reconnecting(
                attempt = 3,
                delayMillis = 30_000,
                nextAttemptAtMillis = 0,
                lastFailure = FailureContext.Closed(
                    code = 1011, reason = "server blew up"
                ),
            )
        )

        // User logs out and back in — the replay-1 SharedFlow re-emits,
        // which should be treated as a fresh session.
        loggedIn.tryEmit(false)
        testScheduler.runCurrent()
        loggedIn.tryEmit(true)
        testScheduler.runCurrent()

        // Without the fix we'd see only 1 created client (Reconnecting is
        // treated as "still active"); with it we see 2.
        assertEquals(
            "Reconnecting client must be closed and replaced on re-login",
            2, factory.created.size
        )
        val second = factory.created[1]
        assertNotSame(first, second)
        assertEquals(1, first.closeCalls.get())
        assertSame(second, manager.activeClient.value)
    }

    @Test
    fun `re-login while client is Connected rebuilds from a clean slate`() = runTest {
        // Regression guard: an earlier version of activate() skipped the
        // rebuild when the existing client was Connected, which meant a
        // re-login *without* an intervening logout would silently keep the
        // previous session's client (and its previous-session auth headers).
        // The new contract is "every loggedIn=true is a clean slate" — the
        // old client must be closed and replaced, even when healthy.
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val first = factory.created.single()

        first.scriptState(WebSocketState.Connected(sinceMillis = 1234L))

        // Re-login (e.g. SessionManager.login() called while already logged
        // in): the Connected old client must be torn down and replaced so
        // the new session never inherits old-session auth.
        loggedIn.tryEmit(true)
        testScheduler.runCurrent()

        assertEquals(
            "Connected client from prior session must be rebuilt on re-login",
            2, factory.created.size
        )
        val second = factory.created[1]
        assertNotSame(first, second)
        assertEquals("old Connected client must have been closed", 1, first.closeCalls.get())
        assertEquals(1, second.connectCalls.get())
        assertSame(second, manager.activeClient.value)
    }

    @Test
    fun `state flow follows the active client across sessions`() = runTest {
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        // Before login — Idle
        testScheduler.runCurrent()
        assertEquals(WebSocketState.Idle, manager.state.value)

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val first = factory.created.single()

        first.scriptState(WebSocketState.Connected(sinceMillis = 1L))
        testScheduler.runCurrent()
        assertTrue(manager.state.value is WebSocketState.Connected)

        loggedIn.tryEmit(false)
        testScheduler.runCurrent()
        // After logout, state falls back to Idle.
        assertEquals(WebSocketState.Idle, manager.state.value)

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val second = factory.created[1]
        second.scriptState(WebSocketState.Connecting)
        testScheduler.runCurrent()
        assertEquals(WebSocketState.Connecting, manager.state.value)
    }

    @Test
    fun `send forwards to the active client`() = runTest {
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        // No session → send returns false without touching anything.
        assertFalse(manager.send("pre-login"))
        assertFalse(manager.send(ByteString.of(1, 2, 3)))

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val client = factory.created.single()

        assertTrue(manager.send("hello"))
        assertTrue(manager.send(ByteString.of(9, 9, 9, 9)))
        assertEquals(listOf("hello"), client.sends)
        assertEquals(listOf(4), client.sendBytesSizes)
    }

    @Test
    fun `sendSuspending returns false when no active session`() = runTest {
        // sendSuspending used to throw IllegalStateException when called
        // with no active session, which broke symmetry with the non-suspending
        // send() that returns false in the same situation. The new contract
        // is: both report failure via Boolean, never throw.
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        assertFalse(
            "sendSuspending must return false, not throw, when no session is active",
            manager.sendSuspending("nope")
        )
        assertFalse(manager.sendSuspending(ByteString.of(1, 2, 3)))
    }

    @Test
    fun `shutdown tears down the active client and is idempotent`() = runTest {
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val client = factory.created.single()

        manager.shutdown()
        assertEquals(1, client.closeCalls.get())
        assertNull(manager.activeClient.value)

        // Calling shutdown() again must not throw. The scope is already
        // cancelled, so no further work happens.
        manager.shutdown()
        assertEquals(1, client.closeCalls.get())
    }

    @Test
    fun `public methods fail loudly after shutdown`() = runTest {
        // Regression guard: an earlier version left the manager in a silent
        // zombie state after close() — started stayed true, scope was
        // cancelled, and a subsequent login never revived the collector.
        // The new contract is: after shutdown(), any further call into a
        // mutating entry point throws IllegalStateException.
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()
        manager.shutdown()

        assertThrows(IllegalStateException::class.java) { manager.start() }
        assertThrows(IllegalStateException::class.java) { manager.send("x") }
        assertThrows(IllegalStateException::class.java) {
            manager.send(ByteString.of(1, 2, 3))
        }
        // For the suspending overloads, `check()` throws before the first
        // suspend point, so we can call them directly inside the outer
        // runTest coroutine and assert on the thrown exception.
        var suspendingTextThrew = false
        try {
            manager.sendSuspending("x")
        } catch (_: IllegalStateException) {
            suspendingTextThrew = true
        }
        assertTrue("sendSuspending(text) must throw after shutdown", suspendingTextThrew)

        var suspendingBytesThrew = false
        try {
            manager.sendSuspending(ByteString.of(1, 2, 3))
        } catch (_: IllegalStateException) {
            suspendingBytesThrew = true
        }
        assertTrue("sendSuspending(bytes) must throw after shutdown", suspendingBytesThrew)
    }

    @Test
    fun `rapid login-logout-login cycles always end with one live client`() = runTest {
        // Use StandardTestDispatcher so emissions are queued then drained in
        // order — closer to how Main.immediate serialises events in prod.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = dispatcher,
        )
        manager.start()

        repeat(5) {
            loggedIn.tryEmit(true)
            loggedIn.tryEmit(false)
        }
        loggedIn.tryEmit(true)
        testScheduler.advanceUntilIdle()

        // We can't guarantee exactly 6 createClient calls because SharedFlow
        // with replay=1 + DROP_OLDEST coalesces consecutive emissions under
        // dispatcher backlog. What we CAN guarantee:
        //   - every client that was created has either been close()d or is the
        //     current active one
        //   - the active one (if any) has connectCalls >= 1 and closeCalls == 0
        val created = factory.created.toList()
        assertTrue("at least one client must have been created", created.isNotEmpty())

        val active = manager.activeClient.value as? FakeWebSocketClient
        for (c in created) {
            if (c === active) {
                assertTrue("active client must be connected", c.connectCalls.get() >= 1)
                assertEquals("active client must not be closed", 0, c.closeCalls.get())
            } else {
                assertEquals(
                    "non-active client ${c.id} must have been closed exactly once",
                    1, c.closeCalls.get()
                )
            }
        }
    }

    @Test
    fun `start after login already emitted activates via replayed value`() = runTest {
        // SessionManager.loggedIn is a SharedFlow with replay=1 precisely so
        // that a late start() — e.g. the process was killed while logged in
        // and cold-started, so the SessionManager pushes the initial `true`
        // into the replay buffer before WebSocketManager.start() runs — still
        // picks the current session up.
        //
        // Regression guard: an earlier wiring used a StateFlow + conflated
        // collect which could miss the initial value depending on dispatcher
        // ordering, making the first post-login session silently connectionless.
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        // Login BEFORE the manager has any chance to observe.
        loggedIn.tryEmit(true)

        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )

        // Still zero clients — start() hasn't run yet.
        assertEquals(0, factory.created.size)
        assertNull(manager.activeClient.value)

        manager.start()
        testScheduler.runCurrent()

        // The replayed `true` must flow through on first collect and
        // activate the session.
        assertEquals(
            "start() must pick up the replayed loggedIn=true",
            1, factory.created.size
        )
        val client = factory.created.single()
        assertSame(client, manager.activeClient.value)
        assertEquals(1, client.connectCalls.get())
    }

    @Test
    fun `events flow emits nothing before a session is active`() = runTest {
        // Documented contract: `events` emits nothing when there is no
        // logged-in session. Verifies the flatMapLatest { it?.events ?:
        // emptyFlow() } guard actually holds — a regression to
        // `it.events` (non-null assertion) would NPE on collect before
        // login.
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        val collected: MutableList<Any> =
            java.util.Collections.synchronizedList(mutableListOf())
        val job = launch { manager.events.collect { collected.add(it) } }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(
            "no events may fire while the user is not logged in",
            0, collected.size
        )
        assertEquals(0, factory.created.size)
    }

    @Test
    fun `incoming flow stops delivering from the old client after re-login`() = runTest {
        // flatMapLatest semantics: when the upstream (_activeClient) emits
        // a new value, the inner subscription to the previous client is
        // cancelled. Concretely: after re-login, any message the old
        // client still emits on its private _incoming SharedFlow must NOT
        // reach the manager's incoming subscribers.
        //
        // Regression guard against accidentally switching to a `merge` /
        // `flatMapMerge` which would keep both inner flows alive and
        // leak stale-session messages into the new session's consumer.
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )
        manager.start()

        val received: MutableList<IncomingMessage> =
            java.util.Collections.synchronizedList(mutableListOf())
        val job = launch { manager.incoming.collect { received.add(it) } }

        // Session 1 — verify the subscriber sees first-session frames.
        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val first = factory.created.single()
        first.pushIncoming(IncomingMessage.Text("from-session-1"))
        testScheduler.runCurrent()
        assertEquals(1, received.size)

        // Re-login — new client takes over. The old client's _incoming
        // must be detached from the manager's inner flatMapLatest collect.
        loggedIn.tryEmit(true)
        testScheduler.runCurrent()
        val second = factory.created[1]
        assertNotSame(first, second)

        // Now have the OLD client emit a message. It must NOT arrive.
        first.pushIncoming(IncomingMessage.Text("leaked-from-old"))
        testScheduler.runCurrent()
        assertEquals(
            "manager must not relay messages from the superseded client",
            1, received.size
        )
        assertEquals("from-session-1", (received[0] as IncomingMessage.Text).text)

        // And the NEW client's messages do arrive.
        second.pushIncoming(IncomingMessage.Text("from-session-2"))
        testScheduler.runCurrent()
        assertEquals(2, received.size)
        assertEquals("from-session-2", (received[1] as IncomingMessage.Text).text)

        job.cancel()
    }

    @Test
    fun `shutdown before start is safe and subsequent start throws`() = runTest {
        // Calling shutdown() on a freshly-constructed manager (no start(),
        // no login) must not throw — there is simply nothing to tear down.
        // A subsequent start() call, however, must fail loudly per the
        // one-way-switch contract.
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = UnconfinedTestDispatcher(testScheduler),
        )

        manager.shutdown() // no start, no login, no client — must be a clean no-op.

        assertEquals(
            "shutdown-before-start must not touch the factory",
            0, factory.created.size
        )
        assertThrows(IllegalStateException::class.java) { manager.start() }
        assertThrows(IllegalStateException::class.java) { manager.send("x") }
    }

    @Test
    fun `concurrent shutdown from multiple threads closes the client exactly once`() {
        // Use real threads (not runTest) so the race is actually raced.
        // Invariants:
        //   1. client.close() fires at most once, regardless of how many
        //      threads simultaneously call shutdown().
        //   2. No thread observes an exception from shutdown().
        //   3. The manager ends in the shut-down state (start() throws).
        val loggedIn = MutableSharedFlow<Boolean>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val factory = RecordingFactory()
        val manager = WebSocketManager(
            loggedIn = loggedIn,
            createClient = factory.factory,
            config = newConfig(),
            parentContext = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        manager.start()
        loggedIn.tryEmit(true)
        // Small spin to let the Unconfined collector activate the client.
        val deadline = System.currentTimeMillis() + 2_000
        while (factory.created.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        val client = factory.created.single()

        val threadCount = 16
        val latch = java.util.concurrent.CountDownLatch(threadCount)
        val startGate = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until threadCount).map {
            Thread {
                try {
                    startGate.await()
                    manager.shutdown()
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    latch.countDown()
                }
            }.also { it.start() }
        }
        startGate.countDown() // release everyone at once
        assertTrue(
            "all shutdown threads must finish promptly",
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        )
        threads.forEach { it.join(1_000) }

        assertTrue(
            "no shutdown caller may throw, but saw: $failures",
            failures.isEmpty(),
        )
        assertEquals(
            "client.close() must fire exactly once regardless of shutdown racers",
            1, client.closeCalls.get(),
        )
        assertNull(manager.activeClient.value)

        // And the one-way-switch invariant still holds after the race.
        assertThrows(IllegalStateException::class.java) { manager.start() }
    }
}
