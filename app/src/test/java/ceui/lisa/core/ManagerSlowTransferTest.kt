package ceui.lisa.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Manager.isSlowTransfer] 的阈值边界。
 *
 * 这几个数决定 `[DL-SLOW]` 什么时候打 —— 它是"卡在最后一点"这类尾部停顿在日志里唯一的
 * 线索。被误改（比如把 300 KB/s 调成 3000）会让真实故障从日志里消失，所以用字面量钉住：
 * 改了阈值就必须显式改这个测试。
 *
 * 阈值与 `Manager.SLOW_TRANSFER_MIN_MS` / `SLOW_TRANSFER_MIN_KBPS` 一致：5000ms / 300KB/s。
 */
class ManagerSlowTransferTest {

    @Test fun `本地复制永远不算慢`() {
        assertFalse(Manager.isSlowTransfer(60_000L, 1L, true))
    }

    @Test fun `时长不足阈值不算慢`() {
        assertFalse(Manager.isSlowTransfer(4_999L, 1L, false))
    }

    @Test fun `没有有效字节不算慢`() {
        assertFalse(Manager.isSlowTransfer(10_000L, 0L, false))
        assertFalse(Manager.isSlowTransfer(10_000L, -1L, false))
    }

    @Test fun `均速达标不算慢`() {
        // 10s 下 10MB ≈ 1000 KB/s
        assertFalse(Manager.isSlowTransfer(10_000L, 10_000_000L, false))
    }

    @Test fun `时长够且均速低才算慢`() {
        // 10s 下 1.5MB ≈ 150 KB/s
        assertTrue(Manager.isSlowTransfer(10_000L, 1_500_000L, false))
    }

    @Test fun `均速恰好在阈值上不算慢（严格小于才报）`() {
        assertFalse(Manager.isSlowTransfer(10_000L, 10_000L * 300, false))
        assertTrue(Manager.isSlowTransfer(10_000L, 10_000L * 300 - 1, false))
    }
}
