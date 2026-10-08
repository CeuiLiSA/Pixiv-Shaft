package ceui.lisa.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Manager.fanOutOthersOnStartOne] —— 单卡「开始」的派发范围。
 *
 * 口径：**目标项永远派发；其它等待项只在全局泵本来就开着时才顺带。**
 *
 * 「泵关着」= 「仅通过 Wi-Fi 下载」在蜂窝上被 `Manager.parkForNetwork()` 熄火（把在传的
 * 翻回 INIT 且**不置 paused**）、冷启动恢复出来的 INIT 项、模式 2「不自动下载」入列的项。
 * 这三种状态下 content 里全是 INIT 且 `isRunning == false`，旧实现 `startOne` 里的
 * `isRunning = true; pumpAvailableSlots();` 会让 `getFirstReady()` 把所有等待项一起挑走 ——
 * 用户「只想先下其中一个」，结果连带数量 = maxConcurrentDownloads，且每条完成后的
 * onFinally 再 pump 一轮继续带走剩下的。
 *
 * 这个函数被误改成恒 `true`，就等于把那个 bug 放回来，所以用字面量钉住。
 */
class ManagerStartOneScopeTest {

    @Test
    fun `泵关着_单卡开始只覆盖被点的那一条`() {
        // 仅 Wi-Fi 切到蜂窝 / 冷启动恢复 / 模式 2 入列 —— 都是 isRunning == false
        assertFalse(Manager.fanOutOthersOnStartOne(false))
    }

    @Test
    fun `泵开着_单卡开始后照常填满并发槽位`() {
        // 模式 0 / 模式 1 且在 Wi-Fi：自动路径本来就该把槽位填满，单卡开始只是其中一次触发
        assertTrue(Manager.fanOutOthersOnStartOne(true))
    }
}