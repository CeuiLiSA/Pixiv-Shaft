package ceui.pixiv.cache

import kotlin.math.roundToInt

/**
 * 图片缓存「预期上限」的换算：纯整数数学，不碰 Android，方便 JVM 单测（对齐 AutoSnapshotQuota）。
 *
 * 与自动快照配额的三点不同：
 *
 * 1. 线性刻度，不是对数。这里的量程只有约 10 倍（100 MB–1 GB），不像自动快照的
 *    10 MB–100 GB（1 万倍）必须上对数才拖得动。线性可预测：拖到哪就是哪，滑条位置
 *    与实际 MB 值一一对应，用户不需要理解「对数」这件事。
 *
 * 2. 没有「不限」档。自动快照的目录只放快照，放开上限无非是多占盘；而 Glide 的
 *    image_manager_disk_cache 同时是「已缓存原图」的落点（详情页原图回填、
 *    ImageTaskRegistry.peekFile 的占位链都吃它），不设上限等于放任它无限膨胀。
 *
 * 3. 它是「预期」而不是「硬上限」。Glide 没有运行时改 maxSize 的公开 API，这个值只在
 *    Glide 初始化时读一次，改完必须重启 App；而且重启后要等第一次成功写缓存
 *    （DiskLruCache.completeEdit 里 size > maxSize 才提交 trim）才会按 LRU 淘汰超出的
 *    部分。所以它不严格约束、也不及时淘汰 —— 文案上用「预期上限」而不是「上限」，
 *    把这个语义写在名字里，不靠小字解释。
 */
object ImageCacheQuota {

    /** 可设置的最小上限（MB）。 */
    const val MIN_LIMIT_MB = 100

    /** 未配置 / 配置损坏时的默认上限（MB），等于 Glide 原生默认，不配置时行为与历史一致。 */
    const val DEFAULT_LIMIT_MB = 250

    /** 可设置的最大上限（MB）= 1 GB。 */
    const val MAX_LIMIT_MB = 1024

    /** 线性刻度：1 MB 一档。滑条 progress 与 MB 是 1:1 的整数偏移。 */
    const val SLIDER_STEPS = MAX_LIMIT_MB - MIN_LIMIT_MB

    const val BYTES_PER_MB = 1024L * 1024L

    /**
     * 只接受合法区间；太小（含遗留的 0 / 负值）回 [DEFAULT_LIMIT_MB]，超出量程收到 [MAX_LIMIT_MB]。
     *
     * 下界刻意不按「收到最近的一端」处理，而是回默认值 —— 与 AutoSnapshotQuota 同一套语义：
     * 小于下界的数只可能来自缺失或损坏的持久化数据，不是用户的真实选择；回默认 250 MB 才能
     * 保住「没配置过 == 与历史行为一致」这条不变式。
     */
    @JvmStatic
    fun clampLimitMb(limitMb: Int): Int = when {
        limitMb < MIN_LIMIT_MB -> DEFAULT_LIMIT_MB
        limitMb > MAX_LIMIT_MB -> MAX_LIMIT_MB
        else -> limitMb
    }

    /** 喂给 InternalCacheDiskCacheFactory 的字节上限。 */
    @JvmStatic
    fun maxBytesForLimit(limitMb: Int): Long = clampLimitMb(limitMb) * BYTES_PER_MB

    /**
     * 把 MB 映射到 0..maxProgress 的滑条进度。
     *
     * 量程按 MIN_LIMIT_MB–MAX_LIMIT_MB 的跨度铺开，而不是从 0 铺到 MAX_LIMIT_MB：后者会让
     * progress 0..90 全部落到 MIN_LIMIT_MB 上，滑条最左边白白多出一段「怎么拖都不变」的
     * 死区。按跨度铺开时，maxProgress == SLIDER_STEPS 就是 1 MB 一格，progress 与 MB 一一对应。
     */
    @JvmStatic
    fun progressForLimitMb(limitMb: Int, maxProgress: Int): Int {
        if (maxProgress <= 0) return 0
        val offset = clampLimitMb(limitMb) - MIN_LIMIT_MB
        return (offset.toFloat() / SLIDER_STEPS * maxProgress).roundToInt()
    }

    /** 把 0..maxProgress 的滑条进度还原成整数 MB。 */
    @JvmStatic
    fun limitMbForProgress(progress: Int, maxProgress: Int): Int {
        if (maxProgress <= 0) return MIN_LIMIT_MB
        val offset = progress.toFloat() / maxProgress * SLIDER_STEPS
        return clampLimitMb(MIN_LIMIT_MB + offset.roundToInt())
    }
}