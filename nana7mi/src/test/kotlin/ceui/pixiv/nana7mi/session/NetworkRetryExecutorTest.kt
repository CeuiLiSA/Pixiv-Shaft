package ceui.pixiv.nana7mi.session

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRetryExecutorTest {

    @Test
    fun `classifier resolves precedence across wrapped exceptions and self-referential causes`() {
        val classifier = TransportFailureClassifier.DEFAULT

        assertEquals(
            TransportFailureKind.DNS,
            classifier.classify(IOException("outer", UnknownHostException("dns"))),
        )
        assertEquals(
            TransportFailureKind.TIMEOUT,
            classifier.classify(IOException("outer", SocketTimeoutException("slow"))),
        )
        assertEquals(
            TransportFailureKind.CONNECT,
            classifier.classify(IOException("outer", ConnectException("refused"))),
        )
        assertEquals(
            TransportFailureKind.CONNECT,
            classifier.classify(NoRouteToHostException("unreachable")),
        )
        assertEquals(
            TransportFailureKind.SOCKET,
            classifier.classify(SocketException("reset")),
        )
        assertEquals(
            TransportFailureKind.CANCELLED,
            classifier.classify(CancellationException("gone")),
        )
        assertFalse(classifier.classify(CancellationException("gone"))!!.retryable)

        val tls = classifier.classify(SSLHandshakeException("cert"))
        assertEquals(TransportFailureKind.TLS, tls)
        assertFalse(tls!!.retryable)

        val interrupted = classifier.classify(InterruptedIOException("canceled"))
        assertEquals(TransportFailureKind.CANCELLED, interrupted)
        assertFalse(interrupted!!.retryable)

        val io = classifier.classify(IOException("generic"))
        assertEquals(TransportFailureKind.IO, io)
        assertTrue(io!!.retryable)

        assertNull(classifier.classify(IllegalStateException("bug")))
    }

    @Test
    fun `idempotent call retries one transient failure and emits diagnostic event`() = runTest {
        val observer = RecordingSessionObserver()
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create { retryDelayMs = 0L },
            clock = FakeSessionClock(),
            observer = observer,
        )
        var calls = 0

        val value = executor.execute(stage = "test") {
            if (++calls == 1) throw ConnectException("refused")
            "ok"
        }

        assertEquals("ok", value)
        assertEquals(2, calls)
        val retryEvent = observer.events.filterIsInstance<SessionEvent.RetryScheduled>().single()
        assertEquals("test", retryEvent.stage)
        assertEquals(TransportFailureKind.CONNECT, retryEvent.failureKind)
        assertEquals(2, retryEvent.attempt)
    }

    @Test
    fun `tls and non-io failures fail fast without replay`() = runTest {
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create { retryDelayMs = 0L },
            clock = FakeSessionClock(),
        )
        var calls = 0
        val tlsError = SSLHandshakeException("certificate")

        val actual = runCatching {
            executor.execute(stage = "test") {
                calls++
                throw tlsError
            }
        }.exceptionOrNull()

        assertSame(tlsError, actual)
        assertEquals(1, calls)
    }

    @Test
    fun `exhausted total budget skips retry`() = runTest {
        val clock = FakeSessionClock()
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create {
                totalBudgetMs = 15_000L
                retryDelayMs = 250L
                minRetryWindowMs = 1_000L
            },
            clock = clock,
        )
        var calls = 0
        val firstFailure = SocketTimeoutException("read timed out")

        val actual = runCatching {
            executor.execute(stage = "slow") {
                calls++
                clock.advanceMillis(14_200L) // leaves 550ms < 1000ms min window
                throw firstFailure
            }
        }.exceptionOrNull()

        assertSame(firstFailure, actual)
        assertEquals(1, calls)
    }

    @Test
    fun `internal retry deadline preserves original transport failure`() = runBlocking {
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create {
                totalBudgetMs = 200L
                retryDelayMs = 0L
                minRetryWindowMs = 50L
            },
        )
        var calls = 0
        val firstFailure = ConnectException("first failure")

        val actual = runCatching {
            executor.execute(stage = "deadline") {
                if (++calls == 1) throw firstFailure
                delay(5_000L)
                "unreachable"
            }
        }.exceptionOrNull()

        assertSame(firstFailure, actual)
        assertEquals(2, calls)
    }

    @Test
    fun `external coroutine cancellation during retry propagates CancellationException`() = runBlocking {
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create {
                totalBudgetMs = 10_000L
                retryDelayMs = 0L
                minRetryWindowMs = 100L
            },
        )
        val enteredRetry = CompletableDeferred<Unit>()
        var caught: Throwable? = null

        val job = launch {
            try {
                var calls = 0
                executor.execute(stage = "cancel") {
                    if (++calls == 1) throw ConnectException("transient")
                    enteredRetry.complete(Unit)
                    delay(10_000L)
                }
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }

        enteredRetry.await()
        job.cancel()
        job.join()

        assertTrue(caught is CancellationException)
    }

    @Test
    fun `result form retries only when extracted failure is retryable`() = runTest {
        data class Result(val error: Throwable? = null, val value: String? = null)
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create { retryDelayMs = 0L },
            clock = FakeSessionClock(),
        )
        var calls = 0

        val result = executor.executeResult(
            stage = "dispatch",
            failureOf = { it.error },
        ) {
            if (++calls == 1) Result(error = ConnectException("refused"))
            else Result(value = "ok")
        }

        assertEquals("ok", result.value)
        assertEquals(2, calls)
    }

    @Test
    fun `NONE policy disables retries completely and newBuilder preserves equality`() = runTest {
        val executor = NetworkRetryExecutor(defaultPolicy = NetworkRetryPolicy.NONE)
        var calls = 0

        val err = runCatching {
            executor.execute(stage = "none") {
                calls++
                throw ConnectException("refused")
            }
        }.exceptionOrNull()

        assertTrue(err is ConnectException)
        assertEquals(1, calls)

        val rebuilt = NetworkRetryPolicy.DEFAULT.newBuilder().retryDelayMs(500L).build()
        assertEquals(500L, rebuilt.retryDelayMs)
        assertEquals(NetworkRetryPolicy.DEFAULT.totalBudgetMs, rebuilt.totalBudgetMs)
    }

    @Test
    fun `zero remaining budget with zero minRetryWindowMs skips retry cleanly`() = runTest {
        val clock = FakeSessionClock()
        val observer = RecordingSessionObserver()
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create {
                totalBudgetMs = 500L
                retryDelayMs = 200L
                minRetryWindowMs = 0L
                maxRetries = 1
            },
            clock = clock,
            observer = observer,
        )
        var calls = 0
        val firstFailure = ConnectException("slow fail")

        val actual = runCatching {
            executor.execute(stage = "zero_budget") {
                calls++
                clock.advanceMillis(500L) // remainingMs == 0
                throw firstFailure
            }
        }.exceptionOrNull()

        assertSame(firstFailure, actual)
        assertEquals(1, calls)
        assertTrue(observer.events.filterIsInstance<SessionEvent.RetryScheduled>().isEmpty())
    }

    @Test
    fun `nested withTimeout inside source propagates instead of masking as retry budget expiry`() = runBlocking {
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create {
                totalBudgetMs = 10_000L
                retryDelayMs = 0L
                minRetryWindowMs = 100L
            },
        )
        var calls = 0

        val actual = runCatching {
            executor.execute(stage = "nested_timeout") {
                if (++calls == 1) throw ConnectException("first attempt failed")
                withTimeout(50L) {
                    delay(2_000L)
                    "unreachable"
                }
            }
        }.exceptionOrNull()

        assertTrue(actual is TimeoutCancellationException)
        assertEquals(2, calls)
    }

    @Test
    fun `internal retry budget expiry with maxRetries greater than 1 aborts immediately with firstError`() = runBlocking {
        val executor = NetworkRetryExecutor(
            defaultPolicy = NetworkRetryPolicy.create {
                totalBudgetMs = 200L
                retryDelayMs = 0L
                minRetryWindowMs = 50L
                maxRetries = 3
            },
            // Fixed clock would still show remainingMs > 0 if the loop mistakenly continued after timeout.
            clock = FakeSessionClock(),
        )
        var calls = 0
        val firstFailure = ConnectException("first failure")

        val actual = runCatching {
            executor.execute(stage = "multi_retry_deadline") {
                if (++calls == 1) throw firstFailure
                delay(5_000L)
                "unreachable"
            }
        }.exceptionOrNull()

        assertSame(firstFailure, actual)
        assertEquals(2, calls)
    }
}
