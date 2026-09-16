package ceui.pixiv.snapshot;

/**
 * 自动快照配额换算：全部是纯整数/浮点数学，不碰 Android，方便 JVM 单测。
 *
 * <p>滑条对外是「10 MB … 类型上限 MB」的整数档，但 {@link Integer#MAX_VALUE} 当「不限制」
 * 哨兵用，传给配额淘汰层时直接换算成 {@link Long#MAX_VALUE} 字节。滑条分两段对数映射：
 * 10 MB–{@link #STANDARD_RANGE_MAX_MB} 占 {@link #STANDARD_RANGE_SLIDER_FRACTION}，
 * 剩余 1/4 留给 10240 MB–不限制；线性铺满 2 PB 会让常用值挤在最左端，没法拖。
 */
public final class AutoSnapshotQuota {

    /** 可设置的最小上限（MB）。 */
    public static final int MIN_LIMIT_MB = 10;

    /** 未配置 / 配置损坏时的默认上限（MB），对齐旧硬编码 200 MB。 */
    public static final int DEFAULT_LIMIT_MB = 200;

    /** 滑条最右端的「类型上限」哨兵；表示不限制。 */
    public static final int UNLIMITED_LIMIT_MB = Integer.MAX_VALUE;

    /** 滑条的离散档数；越大拖动越细腻。 */
    public static final int SLIDER_STEPS = 10000;

    /** 10 MB 到 10240 MB（10 GB）这一段占滑条 3/4。 */
    public static final int STANDARD_RANGE_MAX_MB = 10240;

    /** {@link #STANDARD_RANGE_MAX_MB} 对应的滑条比例。 */
    public static final float STANDARD_RANGE_SLIDER_FRACTION = 0.75f;

    public static final long BYTES_PER_MB = 1024L * 1024L;

    /** 占用 / 上限展示的最小 MB 值：不足这个数的非零占用按它显示，避免写成「0.00 MB」。 */
    public static final double MIN_DISPLAY_MB = 0.01;

    private AutoSnapshotQuota() {
    }

    /** 只接受合法区间；缺失 / 越界都回默认值。 */
    public static int clampLimitMb(int limitMb) {
        if (limitMb < MIN_LIMIT_MB) return DEFAULT_LIMIT_MB;
        return limitMb;
    }

    /** 配额淘汰层使用的字节上限；最右端返回 {@link Long#MAX_VALUE} 表示不限制。 */
    public static long maxBytesForLimit(int limitMb) {
        int clamped = clampLimitMb(limitMb);
        if (clamped == UNLIMITED_LIMIT_MB) return Long.MAX_VALUE;
        return clamped * BYTES_PER_MB;
    }

    /**
     * 展示用的 MB 值。
     *
     * <p>不足 [MIN_DISPLAY_MB] 的**非零**占用按 [MIN_DISPLAY_MB] 显示：原来的
     * 整数 MB 取整会把几百 KB 写成「0 MB」，看起来像一份都没存下来。
     * 真的为 0 时仍然返回 0，不能凭空报出一份占用。
     */
    public static double displayMb(long bytes) {
        if (bytes <= 0L) return 0d;
        double mb = (double) bytes / BYTES_PER_MB;
        return Math.max(mb, MIN_DISPLAY_MB);
    }

    /** 当前 MB 对应的滑条比例，0 = {@link #MIN_LIMIT_MB}，1 = 不限制。 */
    public static float fractionForLimitMb(int limitMb) {
        return (float) fractionForLimitMbValue(clampLimitMb(limitMb));
    }

    /** 由滑条比例还原整数 MB；最右端精确返回 {@link #UNLIMITED_LIMIT_MB}。 */
    public static int limitMbForFraction(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped <= 0f) return MIN_LIMIT_MB;
        if (clamped >= 1f) return UNLIMITED_LIMIT_MB;

        if (clamped <= STANDARD_RANGE_SLIDER_FRACTION) {
            double ratio = Math.pow(
                    (double) STANDARD_RANGE_MAX_MB / MIN_LIMIT_MB,
                    (double) clamped / STANDARD_RANGE_SLIDER_FRACTION);
            long value = Math.round(MIN_LIMIT_MB * ratio);
            if (value <= MIN_LIMIT_MB) return MIN_LIMIT_MB;
            if (value >= STANDARD_RANGE_MAX_MB) return STANDARD_RANGE_MAX_MB;
            return (int) value;
        }

        double ratio = Math.pow(
                (double) UNLIMITED_LIMIT_MB / STANDARD_RANGE_MAX_MB,
                ((double) clamped - STANDARD_RANGE_SLIDER_FRACTION)
                        / (1d - STANDARD_RANGE_SLIDER_FRACTION));
        long value = Math.round(STANDARD_RANGE_MAX_MB * ratio);
        if (value <= STANDARD_RANGE_MAX_MB) return STANDARD_RANGE_MAX_MB;
        if (value >= UNLIMITED_LIMIT_MB) return UNLIMITED_LIMIT_MB;
        return (int) value;
    }

    /** 把 MB 映射到 {@code 0..maxProgress} 的滑条进度。 */
    public static int progressForLimitMb(int limitMb, int maxProgress) {
        if (maxProgress <= 0) return 0;
        return Math.round(fractionForLimitMb(limitMb) * maxProgress);
    }

    /** 把 {@code 0..maxProgress} 的滑条进度还原成整数 MB。 */
    public static int limitMbForProgress(int progress, int maxProgress) {
        if (maxProgress <= 0) return MIN_LIMIT_MB;
        return limitMbForFraction((float) progress / maxProgress);
    }

    private static double fractionForLimitMbValue(int clampedLimitMb) {
        if (clampedLimitMb <= MIN_LIMIT_MB) return 0d;
        if (clampedLimitMb >= UNLIMITED_LIMIT_MB) return 1d;
        if (clampedLimitMb <= STANDARD_RANGE_MAX_MB) {
            double ratio = (double) clampedLimitMb / MIN_LIMIT_MB;
            double span = (double) STANDARD_RANGE_MAX_MB / MIN_LIMIT_MB;
            return (double) STANDARD_RANGE_SLIDER_FRACTION * Math.log(ratio) / Math.log(span);
        }
        double ratio = (double) clampedLimitMb / STANDARD_RANGE_MAX_MB;
        double span = (double) UNLIMITED_LIMIT_MB / STANDARD_RANGE_MAX_MB;
        return (double) STANDARD_RANGE_SLIDER_FRACTION
                + (1d - STANDARD_RANGE_SLIDER_FRACTION) * Math.log(ratio) / Math.log(span);
    }
}
