package ceui.pixiv.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSessionTest {

    private var nowMillis = 1_000_000L
    private val session = AppLockSession { nowMillis * 1_000_000L }

    private fun leaveFor(millis: Long, enabled: Boolean = true, timeout: RelockTimeout = RelockTimeout.IMMEDIATELY) {
        session.onAppBackground()
        nowMillis += millis
        session.onAppForeground(enabled, timeout.millis)
    }

    @Test
    fun `cold start locks only when enabled`() {
        session.start(enabled = false)
        assertFalse(session.isLocked)

        val enabled = AppLockSession { 1L }.apply { start(enabled = true) }
        assertTrue(enabled.isLocked)
    }

    @Test
    fun `immediate timeout relocks on every return`() {
        session.start(enabled = true)
        session.unlock()

        leaveFor(1)

        assertTrue(session.isLocked)
    }

    @Test
    fun `return within timeout stays unlocked, after timeout relocks`() {
        session.start(enabled = true)
        session.unlock()

        leaveFor(59_999, timeout = RelockTimeout.AFTER_1_MINUTE)
        assertFalse(session.isLocked)

        leaveFor(60_000, timeout = RelockTimeout.AFTER_1_MINUTE)
        assertTrue(session.isLocked)
    }

    @Test
    fun `disabled never locks`() {
        leaveFor(10 * 60_000, enabled = false)

        assertFalse(session.isLocked)
    }

    @Test
    fun `leaving during authentication is not leaving the app`() {
        // Android 9 及以下的屏幕锁确认页会让进程走一遍 ON_STOP → ON_START。
        session.start(enabled = true)
        val episode = session.episode
        session.isAuthenticating = true

        leaveFor(5_000)

        assertTrue(session.isLocked)
        assertEquals(episode, session.episode)
    }

    @Test
    fun `returning while still locked starts a new episode so the prompt shows again`() {
        session.start(enabled = true)
        val first = session.episode

        leaveFor(1)

        assertTrue(session.isLocked)
        assertNotEquals(first, session.episode)
    }

    @Test
    fun `episode advances even when the clock does not`() {
        val frozen = AppLockSession { 42L }
        frozen.start(enabled = true)
        val first = frozen.episode
        frozen.onAppBackground()
        frozen.onAppForeground(enabled = true, timeoutMillis = 0L)

        assertTrue(frozen.episode > first)
    }

    @Test
    fun `unlock clears pending authentication`() {
        session.start(enabled = true)
        session.isAuthenticating = true

        session.unlock()

        assertFalse(session.isLocked)
        assertFalse(session.isAuthenticating)
    }

    @Test
    fun `foreground without a recorded background is a no-op`() {
        session.start(enabled = true)
        session.unlock()

        session.onAppForeground(enabled = true, timeoutMillis = 0L)

        assertFalse(session.isLocked)
    }
}
