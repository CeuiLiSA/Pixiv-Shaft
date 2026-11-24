package ceui.pixiv.nana7mi.session

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialExecutionCoordinatorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `concurrent callers never overlap and receive acquire and release events`() = runBlocking {
        val observer = RecordingSessionObserver()
        val coordinator = SerialExecutionCoordinator(observer = observer)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        fun guarded(name: String, entered: CountDownLatch, release: CountDownLatch?) =
            scope.async {
                coordinator.run(name) { lease ->
                    lease.guarded {
                        val nowActive = active.incrementAndGet()
                        maxActive.updateAndGet { old -> maxOf(old, nowActive) }
                        entered.countDown()
                        release?.await(2, TimeUnit.SECONDS)
                        active.decrementAndGet()
                        name
                    }
                }
            }

        val first = guarded("first", firstEntered, releaseFirst)
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        val second = guarded("second", secondEntered, null)

        assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()

        assertEquals("first", withTimeout(2_000) { first.await() })
        assertEquals("second", withTimeout(2_000) { second.await() })
        assertEquals(1, maxActive.get())
        assertEquals(2, observer.events.filterIsInstance<SessionEvent.SerialPermitAcquired>().size)
        assertEquals(2, observer.events.filterIsInstance<SessionEvent.SerialPermitReleased>().size)
    }

    @Test
    fun `cancellation keeps permit until blocking guarded work exits`() = runBlocking {
        val coordinator = SerialExecutionCoordinator()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstTimedOut = AtomicBoolean(false)

        val first = scope.async {
            coordinator.run("cancelled") { lease ->
                lease.guarded {
                    firstEntered.countDown()
                    while (true) {
                        try {
                            if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                                firstTimedOut.set(true)
                            }
                            break
                        } catch (_: InterruptedException) {
                            // Simulates blocking OAuth/persistence completing despite interruption.
                        }
                    }
                    "cancelled"
                }
            }
        }

        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        first.cancel()

        val second = scope.async {
            coordinator.run("next") { lease ->
                lease.guarded {
                    secondEntered.countDown()
                    "next"
                }
            }
        }

        assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()

        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        assertEquals("next", withTimeout(2_000) { second.await() })
        assertFalse(firstTimedOut.get())
    }

    @Test
    fun `cancelling a queued waiter does not leak or corrupt the permit`() = runBlocking {
        val coordinator = SerialExecutionCoordinator()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()

        val first = scope.async {
            coordinator.run("first") {
                order.add("first")
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
                "first"
            }
        }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val cancelledWaiter = scope.async {
            coordinator.run("waiter") {
                order.add("waiter")
                "waiter"
            }
        }
        // Cancel while waiting for the semaphore permit.
        cancelledWaiter.cancel()

        val third = scope.async {
            coordinator.run("third") {
                order.add("third")
                "third"
            }
        }

        releaseFirst.countDown()
        assertEquals("first", withTimeout(2_000) { first.await() })
        assertEquals("third", withTimeout(2_000) { third.await() })
        assertFalse(order.contains("waiter"))
    }

    @Test
    fun `using an escaped lease after run completes throws CancellationException`() = runBlocking {
        val coordinator = SerialExecutionCoordinator()
        var escapedLease: ExecutionLease? = null

        coordinator.run("escape") { lease ->
            escapedLease = lease
            "done"
        }

        val err = runCatching {
            escapedLease!!.guarded { "should-not-run" }
        }.exceptionOrNull()

        assertTrue(err is CancellationException)
    }

    @Test
    fun `throwing observer does not break permit release`() = runBlocking {
        val coordinator = SerialExecutionCoordinator(
            observer = SessionObserver { throw IllegalStateException("observer bug") },
        )

        assertEquals("a", coordinator.run("a") { "a" })
        assertEquals("b", coordinator.run("b") { "b" })
    }

    @Test
    fun `observer cancellation on SerialPermitAcquired still releases permit`() = runBlocking {
        val calls = AtomicInteger(0)
        val coordinator = SerialExecutionCoordinator(
            observer = SessionObserver { event ->
                if (event is SessionEvent.SerialPermitAcquired && calls.incrementAndGet() == 1) {
                    throw CancellationException("observer cancelled on acquire")
                }
            },
        )

        val err = runCatching { coordinator.run("first") { "never" } }.exceptionOrNull()
        assertTrue(err is CancellationException)

        // Permit must not be leaked; next caller must acquire immediately.
        assertEquals("second", withTimeout(1_000) { coordinator.run("second") { "second" } })
    }

    @Test
    fun `composite observer isolates IOException but propagates CancellationException`() {
        var secondCalled = false
        val ioComposite = SessionObserver.composite(
            SessionObserver { throw IOException("log file full") },
            SessionObserver { secondCalled = true },
        )
        ioComposite.onEvent(SessionEvent.EngineClosed)
        assertTrue(secondCalled)

        val cancelComposite = SessionObserver.composite(
            SessionObserver { throw CancellationException("cancelled") },
            SessionObserver { error("must not run after cancellation") },
        )
        val err = runCatching { cancelComposite.onEvent(SessionEvent.EngineClosed) }.exceptionOrNull()
        assertTrue(err is CancellationException)
    }
}
