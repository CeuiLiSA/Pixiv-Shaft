package ceui.lisa.repo

import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Nana7miSearchSerialTest {

    @Test
    fun `borrowed searches never overlap across callers`() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        fun guarded(name: String, entered: CountDownLatch, release: CountDownLatch?) =
            Nana7miSearchSerial.run(name) { _ ->
                Observable.fromCallable {
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { old -> maxOf(old, nowActive) }
                    entered.countDown()
                    release?.await(2, TimeUnit.SECONDS)
                    active.decrementAndGet()
                    name
                }
            }.subscribeOn(Schedulers.io()).test()

        val first = guarded("first", firstEntered, releaseFirst)
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        val second = guarded("second", secondEntered, null)

        assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()

        first.awaitDone(2, TimeUnit.SECONDS).assertValue("first").assertComplete()
        second.awaitDone(2, TimeUnit.SECONDS).assertValue("second").assertComplete()
        assertEquals(1, maxActive.get())
    }

    @Test
    fun `cancel keeps permit until blocking token work has exited`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstTimedOut = AtomicBoolean(false)

        val first = Nana7miSearchSerial.run("cancelled") { lease ->
            lease.blockingObservable {
                firstEntered.countDown()
                while (true) {
                    try {
                        if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                            firstTimedOut.set(true)
                        }
                        break
                    } catch (_: InterruptedException) {
                        // Model refreshTokenBlocking/NonCancellable persistence: disposal may
                        // interrupt the worker, but the critical work still has to finish.
                    }
                }
                "cancelled"
            }
        }.subscribeOn(Schedulers.io()).test()

        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        first.dispose()

        val second = Nana7miSearchSerial.run("next") { _ ->
            Observable.fromCallable {
                secondEntered.countDown()
                "next"
            }
        }.subscribeOn(Schedulers.io()).test()

        assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()

        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        second.awaitDone(2, TimeUnit.SECONDS).assertValue("next").assertComplete()
        assertFalse(firstTimedOut.get())
    }
}
