package ceui.pixiv.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSnapshotQuotaTest {

    @Test
    fun `missing or invalid limit falls back to default`() {
        assertEquals(AutoSnapshotQuota.DEFAULT_LIMIT_MB, AutoSnapshotQuota.clampLimitMb(0))
        assertEquals(AutoSnapshotQuota.DEFAULT_LIMIT_MB, AutoSnapshotQuota.clampLimitMb(-1))
        assertEquals(AutoSnapshotQuota.DEFAULT_LIMIT_MB, AutoSnapshotQuota.clampLimitMb(9))
        assertEquals(10, AutoSnapshotQuota.clampLimitMb(10))
        assertEquals(200, AutoSnapshotQuota.clampLimitMb(200))
    }

    @Test
    fun `max bytes map the unlimited sentinel to long max`() {
        assertEquals(200L * 1024 * 1024, AutoSnapshotQuota.maxBytesForLimit(200))
        assertEquals(10L * 1024 * 1024, AutoSnapshotQuota.maxBytesForLimit(10))
        assertEquals(
            Long.MAX_VALUE,
            AutoSnapshotQuota.maxBytesForLimit(AutoSnapshotQuota.UNLIMITED_LIMIT_MB),
        )
    }

    @Test
    fun `slider endpoints are min and unlimited`() {
        assertEquals(10, AutoSnapshotQuota.limitMbForProgress(0, AutoSnapshotQuota.SLIDER_STEPS))
        assertEquals(
            AutoSnapshotQuota.UNLIMITED_LIMIT_MB,
            AutoSnapshotQuota.limitMbForProgress(
                AutoSnapshotQuota.SLIDER_STEPS,
                AutoSnapshotQuota.SLIDER_STEPS,
            ),
        )
    }

    @Test
    fun `default limit round trips on the log slider`() {
        val progress = AutoSnapshotQuota.progressForLimitMb(
            AutoSnapshotQuota.DEFAULT_LIMIT_MB,
            AutoSnapshotQuota.SLIDER_STEPS,
        )
        assertEquals(200, AutoSnapshotQuota.limitMbForProgress(progress, AutoSnapshotQuota.SLIDER_STEPS))
        assertTrue(progress in 1 until AutoSnapshotQuota.SLIDER_STEPS)
    }

    @Test
    fun `slider value is monotonic and reaches the sentinel`() {
        var previous = AutoSnapshotQuota.limitMbForProgress(0, AutoSnapshotQuota.SLIDER_STEPS)
        for (progress in 1..AutoSnapshotQuota.SLIDER_STEPS) {
            val current = AutoSnapshotQuota.limitMbForProgress(progress, AutoSnapshotQuota.SLIDER_STEPS)
            assertTrue(
                "progress=$progress previous=$previous current=$current",
                current >= previous,
            )
            previous = current
        }
        assertEquals(AutoSnapshotQuota.UNLIMITED_LIMIT_MB, previous)
    }
    @Test
    fun `10 mb to 10240 mb occupies three quarters of slider`() {
        assertEquals(
            0f,
            AutoSnapshotQuota.fractionForLimitMb(AutoSnapshotQuota.MIN_LIMIT_MB),
            0f,
        )
        assertEquals(
            0.75f,
            AutoSnapshotQuota.fractionForLimitMb(AutoSnapshotQuota.STANDARD_RANGE_MAX_MB),
            0.0001f,
        )
        assertEquals(
            1f,
            AutoSnapshotQuota.fractionForLimitMb(AutoSnapshotQuota.UNLIMITED_LIMIT_MB),
            0f,
        )
        assertEquals(
            AutoSnapshotQuota.STANDARD_RANGE_MAX_MB,
            AutoSnapshotQuota.limitMbForFraction(0.75f),
        )
        assertEquals(
            AutoSnapshotQuota.SLIDER_STEPS * 3 / 4,
            AutoSnapshotQuota.progressForLimitMb(
                AutoSnapshotQuota.STANDARD_RANGE_MAX_MB,
                AutoSnapshotQuota.SLIDER_STEPS,
            ),
        )
        assertEquals(
            AutoSnapshotQuota.STANDARD_RANGE_MAX_MB,
            AutoSnapshotQuota.limitMbForProgress(
                AutoSnapshotQuota.SLIDER_STEPS * 3 / 4,
                AutoSnapshotQuota.SLIDER_STEPS,
            ),
        )
    }
    /** 真的没有占用时必须是 0，不能凭空报出一份。 */
    @Test
    fun `zero bytes display as zero mb`() {
        assertEquals(0.0, AutoSnapshotQuota.displayMb(0L), 0.0)
        assertEquals(0.0, AutoSnapshotQuota.displayMb(-1L), 0.0)
    }

    /** 非零但不足 0.01 MB 的占用按 0.01 MB 显示，不再整除成 0 MB。 */
    @Test
    fun `tiny but non-zero usage floors at the minimum displayed mb`() {
        val oneByte = AutoSnapshotQuota.displayMb(1L)
        assertEquals(AutoSnapshotQuota.MIN_DISPLAY_MB, oneByte, 0.0)

        val samples = listOf(1L, 512L, 1024L, 10L * 1024, 1024L * 1024 / 100 - 1)
        for (bytes in samples) {
            val mb = AutoSnapshotQuota.displayMb(bytes)
            assertTrue("bytes=$bytes mb=$mb", mb >= AutoSnapshotQuota.MIN_DISPLAY_MB)
        }
    }

    /** 达到 0.01 MB 之后按真实值显示，不再被最小值顶住。 */
    @Test
    fun `usage above the minimum keeps its real mb value`() {
        assertEquals(0.5, AutoSnapshotQuota.displayMb(512L * 1024), 0.0001)
        assertEquals(1.0, AutoSnapshotQuota.displayMb(1024L * 1024), 0.0001)
        assertEquals(200.0, AutoSnapshotQuota.displayMb(200L * 1024 * 1024), 0.0001)
    }

    /**
     * 两位小数显示时不能出现「0.00 MB」：小于 0.01 MB 的非零占用必须落在
     * 0.01 上，这样舍入到两位小数后写出来的是 0.01 而不是 0.00。
     */
    @Test
    fun `tiny usage survives two decimal rounding`() {
        val samples = listOf(1L, 100L, 1024L, 5L * 1024, 10L * 1024 - 1)
        for (bytes in samples) {
            val mb = AutoSnapshotQuota.displayMb(bytes)
            assertEquals(
                "bytes=$bytes mb=$mb",
                AutoSnapshotQuota.MIN_DISPLAY_MB,
                mb,
                0.0,
            )
        }
    }
}