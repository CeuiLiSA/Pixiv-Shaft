package ceui.lisa.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageReadTimeoutTest {

    /** 默认值 = 该模式滑动条上界，不单独硬编码。 */
    @Test
    fun `default equals the slider max for each mode`() {
        assertEquals(ImageReadTimeout.maxSeconds(false), ImageReadTimeout.defaultSeconds(false))
        assertEquals(ImageReadTimeout.maxSeconds(true), ImageReadTimeout.defaultSeconds(true))
        // 未配置哨兵在两种模式下各自落到该模式的默认值（直连老用户升级不被写成 10s）。
        assertEquals(
            ImageReadTimeout.defaultSeconds(false),
            ImageReadTimeout.clampSeconds(ImageReadTimeout.UNSET_SECONDS, false),
        )
        assertEquals(
            ImageReadTimeout.defaultSeconds(true),
            ImageReadTimeout.clampSeconds(ImageReadTimeout.UNSET_SECONDS, true),
        )
    }

    /** 非直连对齐 OkHttp 默认读超时 10s，保证「没配置过 == 与历史行为一致」。 */
    @Test
    fun `non-direct mode aligns with the okhttp default`() {
        assertEquals(10, ImageReadTimeout.MAX_SECONDS_NON_DIRECT)
        assertEquals(10, ImageReadTimeout.defaultSeconds(false))
    }

    /** 直连保留引入本设置前的硬编码值 30s。 */
    @Test
    fun `direct mode keeps the previous hardcoded value`() {
        assertEquals(30, ImageReadTimeout.MAX_SECONDS_DIRECT)
        assertEquals(30, ImageReadTimeout.defaultSeconds(true))
    }

    @Test
    fun `missing or invalid value falls back to the default`() {
        assertEquals(ImageReadTimeout.defaultSeconds(false), ImageReadTimeout.clampSeconds(0, false))
        assertEquals(ImageReadTimeout.defaultSeconds(false), ImageReadTimeout.clampSeconds(-5, false))
        assertEquals(ImageReadTimeout.defaultSeconds(true), ImageReadTimeout.clampSeconds(0, true))
        assertEquals(1, ImageReadTimeout.clampSeconds(1, false))
        assertEquals(7, ImageReadTimeout.clampSeconds(7, false))
        assertEquals(
            ImageReadTimeout.MAX_SECONDS_NON_DIRECT,
            ImageReadTimeout.clampSeconds(ImageReadTimeout.MAX_SECONDS_NON_DIRECT + 1, false),
        )
        // 直连允许到 30；同一个值在非直连下要被收到 10。
        assertEquals(20, ImageReadTimeout.clampSeconds(20, true))
        assertEquals(
            ImageReadTimeout.MAX_SECONDS_NON_DIRECT,
            ImageReadTimeout.clampSeconds(20, false),
        )
    }

    @Test
    fun `slider endpoints are the range ends for both modes`() {
        for (direct in listOf(false, true)) {
            val steps = ImageReadTimeout.sliderSteps(direct)
            assertEquals(
                ImageReadTimeout.MIN_SECONDS,
                ImageReadTimeout.secondsForProgress(0, direct, steps),
            )
            assertEquals(
                ImageReadTimeout.maxSeconds(direct),
                ImageReadTimeout.secondsForProgress(steps, direct, steps),
            )
            assertEquals(
                0,
                ImageReadTimeout.progressForSeconds(ImageReadTimeout.MIN_SECONDS, direct, steps),
            )
            assertEquals(
                steps,
                ImageReadTimeout.progressForSeconds(ImageReadTimeout.maxSeconds(direct), direct, steps),
            )
        }
    }

    /** 1 秒一档：整段量程里 progress 与秒数一一对应，底部没有「怎么拖都不变」的死区。 */
    @Test
    fun `no dead zone and one step per second`() {
        for (direct in listOf(false, true)) {
            val steps = ImageReadTimeout.sliderSteps(direct)
            assertEquals(
                ImageReadTimeout.maxSeconds(direct) - ImageReadTimeout.MIN_SECONDS,
                steps,
            )
            for (progress in 0..steps) {
                assertEquals(
                    ImageReadTimeout.MIN_SECONDS + progress,
                    ImageReadTimeout.secondsForProgress(progress, direct, steps),
                )
            }
        }
    }

    @Test
    fun `progress and seconds round trip`() {
        for (direct in listOf(false, true)) {
            val steps = ImageReadTimeout.sliderSteps(direct)
            for (seconds in ImageReadTimeout.MIN_SECONDS..ImageReadTimeout.maxSeconds(direct)) {
                val progress = ImageReadTimeout.progressForSeconds(seconds, direct, steps)
                assertEquals(
                    "direct=$direct seconds=$seconds progress=$progress",
                    seconds,
                    ImageReadTimeout.secondsForProgress(progress, direct, steps),
                )
            }
        }
    }

    @Test
    fun `every step stays inside the usable range`() {
        for (direct in listOf(false, true)) {
            val steps = ImageReadTimeout.sliderSteps(direct)
            for (progress in 0..steps) {
                val seconds = ImageReadTimeout.secondsForProgress(progress, direct, steps)
                assertTrue(
                    "direct=$direct progress=$progress seconds=$seconds",
                    seconds in ImageReadTimeout.MIN_SECONDS..ImageReadTimeout.maxSeconds(direct),
                )
            }
        }
    }

    @Test
    fun `zero max progress degrades to the minimum instead of crashing`() {
        assertEquals(ImageReadTimeout.MIN_SECONDS, ImageReadTimeout.secondsForProgress(0, false, 0))
        assertEquals(ImageReadTimeout.MIN_SECONDS, ImageReadTimeout.secondsForProgress(0, true, 0))
        assertEquals(0, ImageReadTimeout.progressForSeconds(5, false, 0))
        assertEquals(0, ImageReadTimeout.progressForSeconds(5, true, 0))
    }

    /** 「调小过」只在值真的离开默认时成立；哨兵 / 越界值都会被 clamp 回默认，因此不算调小。 */
    @Test
    fun `lowered is true only away from the mode default`() {
        for (direct in listOf(false, true)) {
            val default = ImageReadTimeout.defaultSeconds(direct)
            assertFalse(ImageReadTimeout.isLoweredThanDefault(default, direct))
            assertFalse(ImageReadTimeout.isLoweredThanDefault(ImageReadTimeout.UNSET_SECONDS, direct))
            // 越上界会被 clamp 回默认，同样不算「调小过」。
            assertFalse(ImageReadTimeout.isLoweredThanDefault(default + 60, direct))
            assertTrue(ImageReadTimeout.isLoweredThanDefault(ImageReadTimeout.MIN_SECONDS, direct))
            assertTrue(ImageReadTimeout.isLoweredThanDefault(default - 1, direct))
        }
    }
}