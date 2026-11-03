package ceui.pixiv.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalImageCacheQuotaTest {

    @Test
    fun `missing or invalid limit falls back to the default`() {
        assertEquals(OriginalImageCacheQuota.DEFAULT_LIMIT_MB, OriginalImageCacheQuota.clampLimitMb(0))
        assertEquals(OriginalImageCacheQuota.DEFAULT_LIMIT_MB, OriginalImageCacheQuota.clampLimitMb(-1))
        assertEquals(OriginalImageCacheQuota.DEFAULT_LIMIT_MB, OriginalImageCacheQuota.clampLimitMb(199))
        assertEquals(200, OriginalImageCacheQuota.clampLimitMb(200))
        assertEquals(1024, OriginalImageCacheQuota.clampLimitMb(1024))
        assertEquals(
            OriginalImageCacheQuota.MAX_LIMIT_MB,
            OriginalImageCacheQuota.clampLimitMb(OriginalImageCacheQuota.MAX_LIMIT_MB),
        )
        assertEquals(
            OriginalImageCacheQuota.MAX_LIMIT_MB,
            OriginalImageCacheQuota.clampLimitMb(OriginalImageCacheQuota.MAX_LIMIT_MB + 1),
        )
        assertEquals(
            OriginalImageCacheQuota.MAX_LIMIT_MB,
            OriginalImageCacheQuota.clampLimitMb(Int.MAX_VALUE),
        )
    }

    @Test
    fun `default limit equals 1GB`() {
        assertEquals(1024, OriginalImageCacheQuota.DEFAULT_LIMIT_MB)
        assertEquals(
            1024L * 1024 * 1024,
            OriginalImageCacheQuota.maxBytesForLimit(OriginalImageCacheQuota.DEFAULT_LIMIT_MB),
        )
    }

    @Test
    fun `max bytes clamp the same way as the limit`() {
        assertEquals(1024L * 1024 * 1024, OriginalImageCacheQuota.maxBytesForLimit(1))
        assertEquals(4096L * 1024 * 1024, OriginalImageCacheQuota.maxBytesForLimit(8192))
    }

    @Test
    fun `slider endpoints are the range ends`() {
        assertEquals(
            OriginalImageCacheQuota.MIN_LIMIT_MB,
            OriginalImageCacheQuota.limitMbForProgress(0, OriginalImageCacheQuota.SLIDER_STEPS),
        )
        assertEquals(
            OriginalImageCacheQuota.MAX_LIMIT_MB,
            OriginalImageCacheQuota.limitMbForProgress(
                OriginalImageCacheQuota.SLIDER_STEPS,
                OriginalImageCacheQuota.SLIDER_STEPS,
            ),
        )
        assertEquals(
            0,
            OriginalImageCacheQuota.progressForLimitMb(
                OriginalImageCacheQuota.MIN_LIMIT_MB,
                OriginalImageCacheQuota.SLIDER_STEPS,
            ),
        )
        assertEquals(
            OriginalImageCacheQuota.SLIDER_STEPS,
            OriginalImageCacheQuota.progressForLimitMb(
                OriginalImageCacheQuota.MAX_LIMIT_MB,
                OriginalImageCacheQuota.SLIDER_STEPS,
            ),
        )
    }

    @Test
    fun `progress round-trips for every integer mb`() {
        for (limitMb in OriginalImageCacheQuota.MIN_LIMIT_MB..OriginalImageCacheQuota.MAX_LIMIT_MB) {
            val progress = OriginalImageCacheQuota.progressForLimitMb(limitMb, OriginalImageCacheQuota.SLIDER_STEPS)
            val restored = OriginalImageCacheQuota.limitMbForProgress(progress, OriginalImageCacheQuota.SLIDER_STEPS)
            assertEquals(limitMb, restored)
        }
    }

    @Test
    fun `zero progress does not crash`() {
        assertEquals(OriginalImageCacheQuota.MIN_LIMIT_MB, OriginalImageCacheQuota.limitMbForProgress(0, 0))
        assertEquals(0, OriginalImageCacheQuota.progressForLimitMb(1024, 0))
    }
}
