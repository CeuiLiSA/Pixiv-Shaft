package ceui.pixiv.progress

/**
 * 时间源，只用来给进度回调节流。抽出来是为了单测能用假时钟精确控制
 * 「这一次 read 该不该触发回调」，否则节流逻辑只能靠真睡验证。
 */
public fun interface Clock {

    public fun nowMs(): Long

    public companion object {
        /**
         * 单调时钟（`System.nanoTime`）。节流看的是「距上次回调过了多久」，
         * 墙钟被 NTP 校正一下就能让间隔算出负数，所以不用 `currentTimeMillis`。
         */
        @JvmField
        public val MONOTONIC: Clock = Clock { System.nanoTime() / 1_000_000L }
    }
}
