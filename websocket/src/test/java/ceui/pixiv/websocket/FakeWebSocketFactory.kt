package ceui.pixiv.websocket

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Test seam — a fake [WebSocket] factory that bypasses real OkHttp I/O.
 *
 * The factory hands [RobustWebSocketClient] a synthetic socket which:
 *  - fires `onOpen` asynchronously on a single-thread executor so it lands
 *    *after* the client finishes wiring `socketRef` (the listener checks
 *    `socketRef.get() === webSocket` and would otherwise drop the open as
 *    stale)
 *  - records every `send(text)` call into a thread-safe list so tests can
 *    assert on what would have hit the wire
 *  - records `send(bytes)` similarly into [sentBinary]
 *  - reports `queueSize() == 0` so the wire-level high-water-mark loop in
 *    [RobustWebSocketClient.deliver] never pauses
 *
 * This is intentionally *not* a fully general WebSocket fake. It exists to
 * unblock tests whose semantics happen entirely **before** the wire (e.g. the
 * DropOldest backpressure cases, which complete buffer eviction inside
 * [java.nio.channels.Channel.trySend] before any connect happens). Tests that
 * exercise the reconnect / failure / close-frame paths still want the real
 * [TestWebSocketServer].
 *
 * Threading: `onOpen` is dispatched on a dedicated single-thread executor so
 * its lock acquisition cannot recursively deadlock with the connecting
 * thread, and so it is observed *after* `socketRef.set(...)` completes —
 * which is what makes [RobustWebSocketClient.listener]'s identity-gated
 * `onOpen` accept the callback.
 *
 * Instances are stateful — create one per test. Close() cleans up the
 * executor.
 */
internal class FakeWebSocketFactory : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor(
        object : ThreadFactory {
            override fun newThread(r: Runnable) = Thread(r, "FakeWebSocketFactory").apply {
                isDaemon = true
            }
        }
    )

    /** Every fake socket the factory has produced for this test. */
    val sockets: MutableList<FakeWebSocket> = Collections.synchronizedList(mutableListOf())

    /**
     * Drop-in replacement for `OkHttpClient::newWebSocket`. Pass this as the
     * `webSocketFactory` constructor parameter on [RobustWebSocketClient].
     */
    fun asFactory(): (OkHttpClient, Request, WebSocketListener) -> WebSocket =
        { _, request, listener ->
            val socket = FakeWebSocket(request, listener, executor)
            sockets.add(socket)
            socket.scheduleOpen()
            socket
        }

    override fun close() {
        executor.shutdown()
        // Best-effort drain so any in-flight onOpen completes before the
        // test tears the client down.
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}

/**
 * Fake [WebSocket] used by [FakeWebSocketFactory]. Records sends; never echoes.
 */
internal class FakeWebSocket(
    private val request: Request,
    private val listener: WebSocketListener,
    private val executor: java.util.concurrent.Executor,
) : WebSocket {

    private val opened = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val queue = AtomicLong(0)

    /** Texts handed to [send]. Order = the order [RobustWebSocketClient] delivered. */
    val sentText: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Binary frames handed to [send]. */
    val sentBinary: MutableList<ByteString> = Collections.synchronizedList(mutableListOf())

    /**
     * Schedule the synthetic `onOpen`. Posted to [executor] so it runs
     * *after* the synchronous body of `launchConnectLocked` returns —
     * critical because [RobustWebSocketClient]'s listener identity-gates
     * `onOpen` against `socketRef`, which is assigned only after the
     * factory returns.
     */
    fun scheduleOpen() {
        executor.execute {
            if (cancelled.get() || closed.get()) return@execute
            if (opened.compareAndSet(false, true)) {
                // Synthetic 101 Switching Protocols Response. The Response
                // body / code never gets read — only the listener side
                // effects matter to the client.
                val response = Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build()
                listener.onOpen(this, response)
            }
        }
    }

    override fun request(): Request = request

    override fun queueSize(): Long = queue.get()

    override fun send(text: String): Boolean {
        if (cancelled.get() || closed.get()) return false
        sentText.add(text)
        return true
    }

    override fun send(bytes: ByteString): Boolean {
        if (cancelled.get() || closed.get()) return false
        sentBinary.add(bytes)
        return true
    }

    override fun close(code: Int, reason: String?): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        // Fire onClosed synchronously on the executor so the client's
        // listener callbacks land in a predictable order relative to the
        // fake's open.
        executor.execute {
            listener.onClosed(this, code, reason ?: "")
        }
        return true
    }

    override fun cancel() {
        cancelled.set(true)
        // No callback — RobustWebSocketClient's launchConnectLocked calls
        // cancel() on the previous socket purely for cleanup; firing
        // onFailure here would push the client into reconnect mode.
    }
}
