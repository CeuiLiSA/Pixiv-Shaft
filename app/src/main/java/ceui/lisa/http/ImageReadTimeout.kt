package ceui.lisa.http

import kotlin.math.roundToInt

/**
 * 图片「加载」（Glide）与「下载」（Manager）两条链路共用的读超时（秒）统一入口。
 *
 * 这两条链路走的是同一个 OkHttp client（[ceui.lisa.activities.Shaft.getOkHttpClient]）：图片加载
 * 经 LeakSafeOkHttpUrlLoader 用它，下载由 Manager 经 newBuilder() 派生。所以只要把读超时设在那个
 * client 上，两条链路一起生效；下载 client 继承同源值，活跃下载行「已断流N/Ms」的分母
 * （Manager.getDownloadReadTimeoutMillis）也随之跟随。
 *
 * 分直连 / 非直连两种模式，各自对齐引入本设置前的真实行为：
 *
 * - 非直连：1–10s，默认 10s（= OkHttp 默认读超时）；
 * - 直连：1–30s，默认 30s（= 原 Shaft.buildOkHttpClient 直连分支的硬编码值）。
 *
 * 切换直连开关时把用户调过的值重置为**新模式**的默认值（见 Settings.resetImageReadTimeout）：
 * 两种模式量程不同，旧值跨模式不再适用。
 *
 * 「默认值 = 滑动条最大值」：默认不单独硬编码，直接取该模式的上界，避免两处常量漂移。用户只能把
 * 读超时**调小** —— 默认已是该模式最宽松值，调小让对端静默时更早触发超时 → 断点续传重连
 * （「断流立即重连」）。
 *
 * 纯整数数学，不碰 Android，便于 JVM 单测（对齐 [ceui.pixiv.cache.ImageCacheQuota]）。
 */
object ImageReadTimeout {

    /** 最小可调读超时（秒）。 */
    const val MIN_SECONDS = 1

    /**
     * 持久化字段缺失 / 未配置的哨兵值（小于 [MIN_SECONDS]）。
     *
     * 用它当 Settings 字段初值，是为了让「没配置过」在**两种模式**下都落到该模式自己的默认值：直连
     * 用户升级上来读到的是 30s（与历史一致），而不是被写成 10s。[clampSeconds] 会把任何小于
     * [MIN_SECONDS] 的值映射成该模式默认值。
     */
    const val UNSET_SECONDS = 0

    /** 非直连模式的上界 / 默认读超时（秒）= OkHttp 默认 10s。 */
    const val MAX_SECONDS_NON_DIRECT = 10

    /** 直连模式的上界 / 默认读超时（秒）= 原直连分支硬编码的 30s。 */
    const val MAX_SECONDS_DIRECT = 30

    /** 该模式的上界（= 默认值）。 */
    @JvmStatic
    fun maxSeconds(direct: Boolean): Int =
        if (direct) MAX_SECONDS_DIRECT else MAX_SECONDS_NON_DIRECT

    /** 该模式的默认读超时（秒）= 上界，不单独硬编码。 */
    @JvmStatic
    fun defaultSeconds(direct: Boolean): Int = maxSeconds(direct)

    /**
     * 用户是否把读超时**调小过**（当前值 ≠ 该模式默认值）。
     *
     * 默认值 = 该模式上界，所以这里等价于「< 上界」；写成 `!=` 是为了和「非当前模式的默认值」这个
     * 口径字面一致 —— 将来上界与默认值若分离也不会误判。用于「读超时静默重连」的开关：停在默认 =
     * 用户没选激进策略，读超时照旧算失败。
     */
    @JvmStatic
    fun isLoweredThanDefault(seconds: Int, direct: Boolean): Boolean =
        clampSeconds(seconds, direct) != defaultSeconds(direct)

    /** 线性刻度：1 秒一档。滑条档数 = 上界 - 下界。 */
    @JvmStatic
    fun sliderSteps(direct: Boolean): Int = maxSeconds(direct) - MIN_SECONDS

    /**
     * 只接受 [MIN_SECONDS]..[maxSeconds] 的合法区间；太小（含遗留 0 / 负值）回默认，超出量程收到
     * 上界。与 [ceui.pixiv.cache.ImageCacheQuota.clampLimitMb] 同一套语义：小于下界只可能来自缺失
     * 或损坏的持久化数据，不是用户的真实选择，回默认才能保住「没配置过 == 与历史行为一致」。
     */
    @JvmStatic
    fun clampSeconds(seconds: Int, direct: Boolean): Int = when {
        seconds < MIN_SECONDS -> defaultSeconds(direct)
        seconds > maxSeconds(direct) -> maxSeconds(direct)
        else -> seconds
    }

    /**
     * 把秒数映射到 0..maxProgress 的滑条进度。
     *
     * 量程按 MIN_SECONDS..maxSeconds 的跨度铺开，而不是从 0 铺到上界 —— 后者会让最左边一段落到
     * MIN_SECONDS 上，形成「怎么拖都不变」的死区（同 ImageCacheQuota.progressForLimitMb）。
     */
    @JvmStatic
    fun progressForSeconds(seconds: Int, direct: Boolean, maxProgress: Int): Int {
        if (maxProgress <= 0) return 0
        val steps = sliderSteps(direct)
        if (steps <= 0) return 0
        val offset = clampSeconds(seconds, direct) - MIN_SECONDS
        return (offset.toFloat() / steps * maxProgress).roundToInt()
    }

    /** 把 0..maxProgress 的滑条进度还原成整数秒。 */
    @JvmStatic
    fun secondsForProgress(progress: Int, direct: Boolean, maxProgress: Int): Int {
        val steps = sliderSteps(direct)
        if (maxProgress <= 0 || steps <= 0) return MIN_SECONDS
        val offset = progress.toFloat() / maxProgress * steps
        return clampSeconds(MIN_SECONDS + offset.roundToInt(), direct)
    }
}