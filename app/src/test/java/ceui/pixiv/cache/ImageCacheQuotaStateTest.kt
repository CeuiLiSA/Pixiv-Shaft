package ceui.pixiv.cache

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageCacheQuotaStateTest {
    private val appliedField = ImageCacheQuotaState::class.java.getDeclaredField("appliedLimitMb")
        .apply { isAccessible = true }
    private var previous: Any? = null

    @Before
    fun startFreshProcessState() {
        previous = appliedField.get(null)
        appliedField.set(null, null)
    }

    @After
    fun restoreProcessState() {
        appliedField.set(null, previous)
    }

    @Test
    fun `settings changed before the first image load need no restart`() {
        assertFalse(ImageCacheQuotaState.isPendingRestart(500))
        ImageCacheQuotaState.markApplied(500)
        assertFalse(ImageCacheQuotaState.isPendingRestart(500))
    }

    @Test
    fun `changing an active quota and restoring it updates the restart indicator`() {
        ImageCacheQuotaState.markApplied(250)
        assertTrue(ImageCacheQuotaState.isPendingRestart(500))
        assertFalse(ImageCacheQuotaState.isPendingRestart(250))
        ImageCacheQuotaState.markApplied(500)
        assertFalse(ImageCacheQuotaState.isPendingRestart(500))
    }
}
