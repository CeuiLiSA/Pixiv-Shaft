package ceui.pixiv.ui.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class EyeBreakClockTest {
    private val interval = 30 * 60_000L
    private val clock = EyeBreakClock()

    @Test
    fun `series chapter switch keeps counting`() {
        clock.onReaderStarted(0L)
        // 新阅读页先 start,旧的才 stop。
        clock.onReaderStarted(10 * 60_000L)
        clock.onReaderStopped(changingConfigurations = false)
        assertEquals(20 * 60_000L, clock.remainingMs(interval, 10 * 60_000L))
    }

    @Test
    fun `rotation keeps counting`() {
        clock.onReaderStarted(0L)
        clock.onReaderStopped(changingConfigurations = true)
        clock.onReaderStarted(5 * 60_000L)
        assertEquals(25 * 60_000L, clock.remainingMs(interval, 5 * 60_000L))
    }

    @Test
    fun `leaving every reader resets`() {
        clock.onReaderStarted(0L)
        clock.onReaderStopped(changingConfigurations = false)
        clock.onReaderStarted(20 * 60_000L)
        assertEquals(interval, clock.remainingMs(interval, 20 * 60_000L))
    }

    @Test
    fun `restart after reminder counts from now`() {
        clock.onReaderStarted(0L)
        assertEquals(0L, clock.remainingMs(interval, 31 * 60_000L))
        clock.restart(31 * 60_000L)
        assertEquals(interval, clock.remainingMs(interval, 31 * 60_000L))
    }
}
