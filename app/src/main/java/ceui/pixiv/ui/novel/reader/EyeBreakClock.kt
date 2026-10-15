package ceui.pixiv.ui.novel.reader

/**
 * 护眼提醒的「连续阅读」计时(#1241)。必须活得比单个阅读页长:系列跳章是 finish 旧阅读器
 * + 起新 Activity,旋屏会重建 view,两者都不该把计时清零;只有所有阅读页都退到后台才算中断。
 * 只在主线程的生命周期回调里读写。时间由调用方传入(elapsedRealtime),便于单测。
 */
internal class EyeBreakClock {

    private var activeReaders = 0
    private var readingSinceMs: Long? = null

    fun onReaderStarted(nowMs: Long) {
        activeReaders++
        if (readingSinceMs == null) readingSinceMs = nowMs
    }

    /** [changingConfigurations] 为 true(旋屏等)时马上会重建,不算中断。 */
    fun onReaderStopped(changingConfigurations: Boolean) {
        activeReaders = (activeReaders - 1).coerceAtLeast(0)
        if (activeReaders == 0 && !changingConfigurations) readingSinceMs = null
    }

    /** 从 [nowMs] 起重新计:提醒刚弹过,或用户改了间隔。 */
    fun restart(nowMs: Long) {
        if (readingSinceMs != null) readingSinceMs = nowMs
    }

    fun remainingMs(intervalMs: Long, nowMs: Long): Long =
        ((readingSinceMs ?: nowMs) + intervalMs - nowMs).coerceAtLeast(0L)
}
