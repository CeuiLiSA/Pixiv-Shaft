package ceui.lisa.repo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

class Nana7miSearchSerialTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `borrowed searches never overlap across callers`() = runBlocking {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        fun guarded(name: String, entered: CountDownLatch, release: CountDownLatch?) =
            scope.async {
                Nana7miSearchSerial.run(name) { lease ->
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
    }

    @Test
    fun `cancel keeps permit until blocking token work has exited`() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstTimedOut = AtomicBoolean(false)

        val first = scope.async {
            Nana7miSearchSerial.run("cancelled") { lease ->
                lease.guarded {
                    firstEntered.countDown()
                    while (true) {
                        try {
                            if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                                firstTimedOut.set(true)
                            }
                            break
                        } catch (_: InterruptedException) {
                            // Model refreshTokenBlocking/NonCancellable persistence: cancellation
                            // may interrupt the worker, but the critical work still has to finish.
                        }
                    }
                    "cancelled"
                }
            }
        }

        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        first.cancel()

        val second = scope.async {
            Nana7miSearchSerial.run("next") { lease ->
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
}
