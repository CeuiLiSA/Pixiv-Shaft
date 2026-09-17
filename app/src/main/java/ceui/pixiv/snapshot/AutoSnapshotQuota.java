package ceui.pixiv.snapshot;

/**
 * 自动快照配额换算：全部是纯整数/浮点数学，不碰 Android，方便 JVM 单测。
 *
 * <p>滑条的有限量程是 {@link #MIN_LIMIT_MB}–{@link #MAX_LIMIT_MB}（10 MB–100 GB）的整数
 * MB，走对数刻度；最右那**一档**（{@code progress == maxProgress}）单独留给「不限」，
 * 对外用 {@link #UNLIMITED_LIMIT_MB} 当哨兵，传给淘汰层时换算成 {@link Long#MAX_VALUE} 字节。
 *
 * <p>为什么是对数：线性铺满量程会把默认的 200 MB 挤在最左端一个像素里，没法拖。
 * 为什么有限端封在 100 GB：手机上的自动快照不可能超过这个量级，再往上只有「不限」有意义；
 * 让量程一路开到 {@link Integer#MAX_VALUE} MB（约 2 PB）的话，滑条右侧会有一大段全是
 * 「4473 GB」「2086914 GB」这种取不到也用不上的值，还容易在想选「不限」时误停在旁边。
 *
 * <p>单段对数刻度本身就把常用值摊得很开，不需要再分段：10 MB 在最左，1 GB 正好落在中点，
 * 10 GB 落在 3/4 处，100 GB 在有限端最右。
 */
public final class AutoSnapshotQuota {

    /** 可设置的最小上限（MB）。 */
    public static final int MIN_LIMIT_MB = 10;

    /** 可设置的最大**有限**上限（MB）= 100 GB；再往上只有「不限」。 */
    public static final int MAX_LIMIT_MB = 100 * 1024;

    /** 未配置 / 配置损坏时的默认上限（MB），对齐旧硬编码 200 MB。 */
    public static final int DEFAULT_LIMIT_MB = 200;

    /** 滑条最右**一档**的哨兵；表示不限制。它不在有限量程内，也不由比例换算产出。 */
    public static final int UNLIMITED_LIMIT_MB = Integer.MAX_VALUE;

    /** 滑条的离散档数；越大拖动越细腻。最后一档是「不限」，其余 {@code SLIDER_STEPS} 档铺有限量程。 */
    public static final int SLIDER_STEPS = 10000;

    public static final long BYTES_PER_MB = 1024L * 1024L;

    /** 占用 / 上限展示的最小 MB 值：不足这个数的非零占用按它显示，避免写成「0.00 MB」。 */
    public static final double MIN_DISPLAY_MB = 0.01;

    private AutoSnapshotQuota() {
    }

    /** 只接受合法区间；缺失 / 太小回默认值，超出有限量程收到 {@link #MAX_LIMIT_MB}。 */
    public static int clampLimitMb(int limitMb) {
        if (limitMb == UNLIMITED_LIMIT_MB) return UNLIMITED_LIMIT_MB;
        if (limitMb < MIN_LIMIT_MB) return DEFAULT_LIMIT_MB;
        if (limitMb > MAX_LIMIT_MB) return MAX_LIMIT_MB;
        return limitMb;
    }

    /** 配额淘汰层使用的字节上限；「不限」返回 {@link Long#MAX_VALUE}。 */
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

    /**
     * 当前 MB 在**有限量程**里的比例，0 = {@link #MIN_LIMIT_MB}，1 = {@link #MAX_LIMIT_MB}。
     * 「不限」也返回 1（它在滑条上就在最右边），但反过来 1 只还原成 {@link #MAX_LIMIT_MB}
     * —— 哨兵只由 {@link #progressForLimitMb} / {@link #limitMbForProgress} 的最后一档表达。
     */
    public static float fractionForLimitMb(int limitMb) {
        return (float) fractionForLimitMbValue(clampLimitMb(limitMb));
    }

    /** 由比例还原有限量程内的整数 MB。 */
    public static int limitMbForFraction(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped <= 0f) return MIN_LIMIT_MB;
        if (clamped >= 1f) return MAX_LIMIT_MB;

        double ratio = Math.pow((double) MAX_LIMIT_MB / MIN_LIMIT_MB, clamped);
        long value = Math.round(MIN_LIMIT_MB * ratio);
        if (value <= MIN_LIMIT_MB) return MIN_LIMIT_MB;
        if (value >= MAX_LIMIT_MB) return MAX_LIMIT_MB;
        return (int) value;
    }

    /** 把 MB 映射到 {@code 0..maxProgress} 的滑条进度；「不限」占最后一档。 */
    public static int progressForLimitMb(int limitMb, int maxProgress) {
        if (maxProgress <= 0) return 0;
        int clamped = clampLimitMb(limitMb);
        if (clamped == UNLIMITED_LIMIT_MB) return maxProgress;
        return Math.round(fractionForLimitMb(clamped) * (maxProgress - 1));
    }

    /** 把 {@code 0..maxProgress} 的滑条进度还原成整数 MB；最后一档是「不限」。 */
    public static int limitMbForProgress(int progress, int maxProgress) {
        if (maxProgress <= 0) return MIN_LIMIT_MB;
        if (progress >= maxProgress) return UNLIMITED_LIMIT_MB;
        if (progress <= 0) return MIN_LIMIT_MB;
        return limitMbForFraction((float) progress / (maxProgress - 1));
    }

    private static double fractionForLimitMbValue(int clampedLimitMb) {
        if (clampedLimitMb <= MIN_LIMIT_MB) return 0d;
        if (clampedLimitMb >= MAX_LIMIT_MB) return 1d;
        double ratio = (double) clampedLimitMb / MIN_LIMIT_MB;
        double span = (double) MAX_LIMIT_MB / MIN_LIMIT_MB;
        return Math.log(ratio) / Math.log(span);
    }
}
