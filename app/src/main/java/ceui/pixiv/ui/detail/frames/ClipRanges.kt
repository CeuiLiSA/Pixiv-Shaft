package ceui.pixiv.ui.detail.frames

/**
 * 逐帧页「片段」的范围规则，纯函数，JVM 单测覆盖（`ClipRangesTest`）。
 *
 * 范围一律是闭区间帧序号；`n` 是总帧数。时间轴把手、「设为起点 / 终点」按钮和
 * SavedStateHandle 里恢复出来的旧值都走这里，三处的边界规则只有这一份。
 */
internal object ClipRanges {

    /** 片段至少两帧：一帧就是「选帧」，而且 MP4 编码器也不收单帧。 */
    const val MIN_FRAMES = 2

    /** 把存下来的原始值（可能为空、可能来自帧数不同的旧版本）夹成合法范围；没存过就是整段。 */
    fun clamp(raw: IntArray?, n: Int): IntRange {
        if (raw == null || raw.size < 2 || n < MIN_FRAMES) return 0 until n
        val start = raw[0].coerceIn(0, n - MIN_FRAMES)
        return start..raw[1].coerceIn(start + MIN_FRAMES - 1, n - 1)
    }

    /** 「设为起点」：起点越过终点（留不出两帧）时，终点退回最后一帧 —— 剪辑软件入点越过出点的惯例。 */
    fun withStart(current: IntRange, frame: Int, n: Int): IntRange {
        if (n < MIN_FRAMES) return 0 until n
        val last = n - 1
        val start = frame.coerceIn(0, last - MIN_FRAMES + 1)
        val end = if (current.last < start + MIN_FRAMES - 1) last else current.last
        return start..end
    }

    /** 「设为终点」：终点越过起点时，起点退回第一帧。 */
    fun withEnd(current: IntRange, frame: Int, n: Int): IntRange {
        if (n < MIN_FRAMES) return 0 until n
        val end = frame.coerceIn(MIN_FRAMES - 1, n - 1)
        val start = if (current.first > end - MIN_FRAMES + 1) 0 else current.first
        return start..end
    }

    /**
     * 拖把手：把手落到帧边界 [boundary]（0..n，n = 末尾）时的新范围。把手推不过对面那一端，
     * 始终留够 [MIN_FRAMES] 帧。
     */
    fun dragStart(current: IntRange, boundary: Int): IntRange =
        boundary.coerceIn(0, maxOf(0, current.last - MIN_FRAMES + 1))..current.last

    fun dragEnd(current: IntRange, boundary: Int, n: Int): IntRange =
        current.first..(boundary - 1).coerceIn(minOf(current.first + MIN_FRAMES - 1, n - 1), n - 1)

    /** `_frame07`：序号从 1 起，按总帧数补零，文件管理器里按名排序即按时间。 */
    fun frameSuffix(i: Int, count: Int): String = "_frame" + pad(i + 1, count)

    /** `_clip03-09`：首末帧序号，补零同 [frameSuffix]。 */
    fun clipSuffix(range: IntRange, count: Int): String =
        "_clip" + pad(range.first + 1, count) + "-" + pad(range.last + 1, count)

    private fun pad(value: Int, count: Int): String =
        value.toString().padStart(maxOf(2, count.toString().length), '0')
}
