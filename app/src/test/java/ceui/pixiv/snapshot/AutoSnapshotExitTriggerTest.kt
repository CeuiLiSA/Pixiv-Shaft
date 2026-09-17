package ceui.pixiv.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AutoSnapshotEngine.exitTriggerSignal] 的纯 JVM 判定测试。
 *
 * 生成时机改成「真离开才评估」之后，两个信号共用这一个判定点，所以这里钉住优先级：
 * 长时间驻留优先，其次反复进入，都没有就什么都不做。
 */
class AutoSnapshotExitTriggerTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `short dwell without revisit signal triggers nothing`() {
        val record = AutoSnapshotBehaviorRecord(illustId = 42L).withVisit(now)

        assertNull(AutoSnapshotEngine.exitTriggerSignal(dwellMs = 10_000L, record = record))
    }

    @Test
    fun `dwell threshold alone is enough`() {
        assertNull(AutoSnapshotEngine.exitTriggerSignal(dwellMs = 59_999L, record = null))
        assertEquals(
            AutoSnapshotBehaviorStore.SIGNAL_DWELL,
            AutoSnapshotEngine.exitTriggerSignal(dwellMs = 60_000L, record = null),
        )
    }

    @Test
    fun `revisit threshold alone is enough`() {
        val twoVisits = AutoSnapshotBehaviorRecord(illustId = 42L)
            .withVisit(now)
            .withVisit(now + 1_000L)

        assertNull(AutoSnapshotEngine.exitTriggerSignal(dwellMs = 1_000L, record = twoVisits))

        val threeVisits = twoVisits.withVisit(now + 2_000L)
        assertEquals(
            AutoSnapshotBehaviorStore.SIGNAL_REVISIT,
            AutoSnapshotEngine.exitTriggerSignal(dwellMs = 1_000L, record = threeVisits),
        )
    }

    @Test
    fun `dwell wins when both signals are met`() {
        val record = AutoSnapshotBehaviorRecord(illustId = 42L)
            .withVisit(now)
            .withVisit(now + 1_000L)
            .withVisit(now + 2_000L)

        assertEquals(
            AutoSnapshotBehaviorStore.SIGNAL_DWELL,
            AutoSnapshotEngine.exitTriggerSignal(dwellMs = 60_000L, record = record),
        )
    }

    @Test
    fun `missing record still allows the dwell signal`() {
        assertEquals(
            AutoSnapshotBehaviorStore.SIGNAL_DWELL,
            AutoSnapshotEngine.exitTriggerSignal(dwellMs = 90_000L, record = null),
        )
    }
}
