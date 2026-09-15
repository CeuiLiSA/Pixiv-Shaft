package ceui.pixiv.shaftapi

/** One initial update, at most ten UI updates per second, and the final byte count. */
internal class MediaUploadProgress {
    private var lastPercent = -1L
    private var lastUpdate = 0L

    fun shouldReport(sent: Long, total: Long, nowNanos: Long): Boolean {
        val percent = sent * 100 / total
        if (percent == lastPercent) return false
        if (lastPercent >= 0 && sent < total && nowNanos - lastUpdate < 100_000_000L) return false
        lastPercent = percent
        lastUpdate = nowNanos
        return true
    }
}
