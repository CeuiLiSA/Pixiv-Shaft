package ceui.pixiv.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCacheQuotaTest {

    @Test
    fun `missing or invalid limit falls back to the default`() {
        assertEquals(ImageCacheQuota.DEFAULT_LIMIT_MB, ImageCacheQuota.clampLimitMb(0))
        assertEquals(ImageCacheQuota.DEFAULT_LIMIT_MB, ImageCacheQuota.clampLimitMb(-1))
        assertEquals(ImageCacheQuota.DEFAULT_LIMIT_MB, ImageCacheQuota.clampLimitMb(99))
        assertEquals(100, ImageCacheQuota.clampLimitMb(100))
        assertEquals(250, ImageCacheQuota.clampLimitMb(250))
        assertEquals(
            ImageCacheQuota.MAX_LIMIT_MB,
            ImageCacheQuota.clampLimitMb(ImageCacheQuota.MAX_LIMIT_MB),
        )
        assertEquals(
            ImageCacheQuota.MAX_LIMIT_MB,
            ImageCacheQuota.clampLimitMb(ImageCacheQuota.MAX_LIMIT_MB + 1),
        )
        assertEquals(
            ImageCacheQuota.MAX_LIMIT_MB,
            ImageCacheQuota.clampLimitMb(Int.MAX_VALUE),
        )
    }

    /** 默认值必须等于 Glide 原生默认，否则「不配置」这个状态就不再是行为不变的。 */
    @Test
    fun `default limit equals the glide native default`() {
        assertEquals(250, ImageCacheQuota.DEFAULT_LIMIT_MB)
        assertEquals(
            250L * 1024 * 1024,
            ImageCacheQuota.maxBytesForLimit(ImageCacheQuota.DEFAULT_LIMIT_MB),
        )
    }

    @Test
    fun `max bytes clamp the same way as the limit`() {
        assertEquals(250L * 1024 * 1024, ImageCacheQuota.maxBytesForLimit(1))
        assertEquals(1024L * 1024 * 1024, ImageCacheQuota.maxBytesForLimit(4096))
    }

    /** 两端就是量程两端；这里没有自动快照那种「不限」哨兵。 */
    @Test
    fun `slider endpoints are the range ends`() {
        assertEquals(
            ImageCacheQuota.MIN_LIMIT_MB,
            ImageCacheQuota.limitMbForProgress(0, ImageCacheQuota.SLIDER_STEPS),
        )
        assertEquals(
            ImageCacheQuota.MAX_LIMIT_MB,
            ImageCacheQuota.limitMbForProgress(
                ImageCacheQuota.SLIDER_STEPS,
                ImageCacheQuota.SLIDER_STEPS,
            ),
        )
        assertEquals(
            0,
            ImageCacheQuota.progressForLimitMb(
                ImageCacheQuota.MIN_LIMIT_MB,
                ImageCacheQuota.SLIDER_STEPS,
            ),
        )
        assertEquals(
            ImageCacheQuota.SLIDER_STEPS,
            ImageCacheQuota.progressForLimitMb(
                ImageCacheQuota.MAX_LIMIT_MB,
                ImageCacheQuota.SLIDER_STEPS,
            ),
        )
    }

    /**
     * 量程必须按 MIN–MAX 的跨度铺开。若按 0–MAX 铺，最左 90 档会全部落在 100 MB 上，
     * 滑条左边白白多出一段「怎么拖都不变」的死区。
     */
    @Test
    fun `no dead zone at the bottom of the slider`() {
        assertEquals(
            ImageCacheQuota.MIN_LIMIT_MB + 1,
            ImageCacheQuota.limitMbForProgress(1, ImageCacheQuota.SLIDER_STEPS),
        )
        val distinct = (0..20)
            .map { ImageCacheQuota.limitMbForProgress(it, ImageCacheQuota.SLIDER_STEPS) }
            .toSet()
        assertEquals(21, distinct.size)
    }

    @Test
    fun `progress and mb round trip`() {
        for (limitMb in listOf(100, 101, 250, 512, 1023, 1024)) {
            val progress = ImageCacheQuota.progressForLimitMb(limitMb, ImageCacheQuota.SLIDER_STEPS)
            assertEquals(
                "limitMb=$limitMb progress=$progress",
                limitMb,
                ImageCacheQuota.limitMbForProgress(progress, ImageCacheQuota.SLIDER_STEPS),
            )
        }
    }

    @Test
    fun `slider value is monotonic across the whole range`() {
        var previous = ImageCacheQuota.limitMbForProgress(0, ImageCacheQuota.SLIDER_STEPS)
        for (progress in 1..ImageCacheQuota.SLIDER_STEPS) {
            val current = ImageCacheQuota.limitMbForProgress(progress, ImageCacheQuota.SLIDER_STEPS)
            assertTrue(
                "progress=$progress previous=$previous current=$current",
                current >= previous,
            )
            previous = current
        }
        assertEquals(ImageCacheQuota.MAX_LIMIT_MB, previous)
    }

    @Test
    fun `every step stays inside the usable range`() {
        for (progress in 0..ImageCacheQuota.SLIDER_STEPS) {
            val value = ImageCacheQuota.limitMbForProgress(progress, ImageCacheQuota.SLIDER_STEPS)
            assertTrue(
                "progress=$progress value=$value",
                value in ImageCacheQuota.MIN_LIMIT_MB..ImageCacheQuota.MAX_LIMIT_MB,
            )
        }
    }

    @Test
    fun `zero max progress degrades to the minimum instead of crashing`() {
        assertEquals(ImageCacheQuota.MIN_LIMIT_MB, ImageCacheQuota.limitMbForProgress(0, 0))
        assertEquals(0, ImageCacheQuota.progressForLimitMb(250, 0))
    }
}