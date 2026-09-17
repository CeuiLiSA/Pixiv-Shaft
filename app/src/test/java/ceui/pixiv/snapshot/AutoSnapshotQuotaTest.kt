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

    /** 哨兵要原样活着；介于有限量程上限和哨兵之间的值收到有限上限，不能悄悄变成「不限」。 */
    @Test
    fun `out of range limit clamps to the finite maximum`() {
        assertEquals(
            AutoSnapshotQuota.UNLIMITED_LIMIT_MB,
            AutoSnapshotQuota.clampLimitMb(AutoSnapshotQuota.UNLIMITED_LIMIT_MB),
        )
        assertEquals(
            AutoSnapshotQuota.MAX_LIMIT_MB,
            AutoSnapshotQuota.clampLimitMb(AutoSnapshotQuota.MAX_LIMIT_MB),
        )
        assertEquals(
            AutoSnapshotQuota.MAX_LIMIT_MB,
            AutoSnapshotQuota.clampLimitMb(AutoSnapshotQuota.MAX_LIMIT_MB + 1),
        )
        assertEquals(
            AutoSnapshotQuota.MAX_LIMIT_MB,
            AutoSnapshotQuota.clampLimitMb(AutoSnapshotQuota.UNLIMITED_LIMIT_MB - 1),
        )
    }

    @Test
    fun `max bytes map the unlimited sentinel to long max`() {
        assertEquals(200L * 1024 * 1024, AutoSnapshotQuota.maxBytesForLimit(200))
        assertEquals(10L * 1024 * 1024, AutoSnapshotQuota.maxBytesForLimit(10))
        assertEquals(
            100L * 1024 * 1024 * 1024,
            AutoSnapshotQuota.maxBytesForLimit(AutoSnapshotQuota.MAX_LIMIT_MB),
        )
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

    /**
     * 「不限」只占最后一档：倒数第二档必须还是有限值，而且正好是有限量程的上限。
     * 否则滑条右侧会出现一片「取到了但其实等于不限」的死区。
     */
    @Test
    fun `unlimited occupies only the last slider step`() {
        assertEquals(
            AutoSnapshotQuota.MAX_LIMIT_MB,
            AutoSnapshotQuota.limitMbForProgress(
                AutoSnapshotQuota.SLIDER_STEPS - 1,
                AutoSnapshotQuota.SLIDER_STEPS,
            ),
        )
        assertEquals(
            AutoSnapshotQuota.SLIDER_STEPS - 1,
            AutoSnapshotQuota.progressForLimitMb(
                AutoSnapshotQuota.MAX_LIMIT_MB,
                AutoSnapshotQuota.SLIDER_STEPS,
            ),
        )
        assertEquals(
            AutoSnapshotQuota.SLIDER_STEPS,
            AutoSnapshotQuota.progressForLimitMb(
                AutoSnapshotQuota.UNLIMITED_LIMIT_MB,
                AutoSnapshotQuota.SLIDER_STEPS,
            ),
        )
    }

    /** 有限量程内的每一档都必须是真能用上的值，不能冒出比 100 GB 还大的数。 */
    @Test
    fun `every finite step stays inside the usable range`() {
        for (progress in 0 until AutoSnapshotQuota.SLIDER_STEPS) {
            val value = AutoSnapshotQuota.limitMbForProgress(progress, AutoSnapshotQuota.SLIDER_STEPS)
            assertTrue(
                "progress=$progress value=$value",
                value in AutoSnapshotQuota.MIN_LIMIT_MB..AutoSnapshotQuota.MAX_LIMIT_MB,
            )
        }
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

    /**
     * 单段对数刻度本身就把常用值摊开了：1 GB 在中点、10 GB 在 3/4 处。
     * 这正是原来那套「10 MB–10 GB 占 75%」分段想要的效果，所以分段可以不要。
     */
    @Test
    fun `log scale spreads the everyday values`() {
        assertEquals(0f, AutoSnapshotQuota.fractionForLimitMb(AutoSnapshotQuota.MIN_LIMIT_MB), 0f)
        assertEquals(0.5f, AutoSnapshotQuota.fractionForLimitMb(1024), 0.01f)
        assertEquals(0.75f, AutoSnapshotQuota.fractionForLimitMb(10 * 1024), 0.01f)
        assertEquals(1f, AutoSnapshotQuota.fractionForLimitMb(AutoSnapshotQuota.MAX_LIMIT_MB), 0f)
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
