package ceui.pixiv.cache

import kotlin.math.roundToInt

/**
 * 原图缓存上限的数值转换：纯整数数学，无 Android 依赖，便于 JVM 单元测试。
 */
object OriginalImageCacheQuota {

    /** 可设置的最小上限（MB）。 */
    const val MIN_LIMIT_MB = 200

    /** 默认上限（MB）= 1 GB。 */
    const val DEFAULT_LIMIT_MB = 1024

    /** 可设置的最大上限（MB）= 4 GB。 */
    const val MAX_LIMIT_MB = 4096

    /** 线性刻度步数。 */
    const val SLIDER_STEPS = MAX_LIMIT_MB - MIN_LIMIT_MB

    const val BYTES_PER_MB = 1024L * 1024L

    @JvmStatic
    fun clampLimitMb(limitMb: Int): Int = when {
        limitMb < MIN_LIMIT_MB -> DEFAULT_LIMIT_MB
        limitMb > MAX_LIMIT_MB -> MAX_LIMIT_MB
        else -> limitMb
    }

    @JvmStatic
    fun maxBytesForLimit(limitMb: Int): Long = clampLimitMb(limitMb) * BYTES_PER_MB

    @JvmStatic
    fun progressForLimitMb(limitMb: Int, maxProgress: Int): Int {
        if (maxProgress <= 0) return 0
        val offset = clampLimitMb(limitMb) - MIN_LIMIT_MB
        return (offset.toFloat() / SLIDER_STEPS * maxProgress).roundToInt()
    }

    @JvmStatic
    fun limitMbForProgress(progress: Int, maxProgress: Int): Int {
        if (maxProgress <= 0) return MIN_LIMIT_MB
        val offset = progress.toFloat() / maxProgress * SLIDER_STEPS
        return clampLimitMb(MIN_LIMIT_MB + offset.roundToInt())
    }
}
