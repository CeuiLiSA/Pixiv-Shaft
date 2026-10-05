package ceui.lisa.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动启动」闸门真值表 —— 纯 JVM，不碰 Settings / Android。
 *
 * 口径：闸门只守「入列该不该自动启动下载」。
 *   0 无限制       → 任意网络都自动开始
 *   1 仅通过 Wi-Fi  → 仅 Wi-Fi 下自动开始
 *   2 不自动下载    → 从不自动开始，等用户在下载管理里手动启动（手动不受网络限制）
 */
class DownloadLimitTypeUtilTest {

    @Test
    fun `无限制 任意网络都自动开始`() {
        assertTrue(DownloadLimitTypeUtil.autoStartAllowed(0, true))
        assertTrue(DownloadLimitTypeUtil.autoStartAllowed(0, false))
    }

    @Test
    fun `仅WiFi 只有 Wi-Fi 下才自动开始`() {
        assertTrue(DownloadLimitTypeUtil.autoStartAllowed(1, true))
        assertFalse(DownloadLimitTypeUtil.autoStartAllowed(1, false))
    }

    @Test
    fun `不自动下载 任何网络都不自动开始`() {
        assertFalse(DownloadLimitTypeUtil.autoStartAllowed(2, true))
        assertFalse(DownloadLimitTypeUtil.autoStartAllowed(2, false))
    }

    @Test
    fun `脏值按无限制兜底 不锁死用户`() {
        assertTrue(DownloadLimitTypeUtil.autoStartAllowed(-1, false))
        assertTrue(DownloadLimitTypeUtil.autoStartAllowed(99, false))
    }

    @Test
    fun `只有仅WiFi模式需要网络守门`() {
        assertTrue(DownloadLimitTypeUtil.requiresWifi(1))
        assertFalse(DownloadLimitTypeUtil.requiresWifi(0))
        assertFalse(DownloadLimitTypeUtil.requiresWifi(2))
    }
}
