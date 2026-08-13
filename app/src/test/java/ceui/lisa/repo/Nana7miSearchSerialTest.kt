package ceui.lisa.repo

import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            Nana7miSearchSerial.run(name) {
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
}
