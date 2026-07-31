package ceui.pixiv.download.template

/**
 * How `{page}` turns into digits. Both knobs are global (they live on
 * [ceui.pixiv.download.config.DownloadConfig], not per-bucket) because a user
 * who wants `p01` wants it everywhere — mixing padded and unpadded names inside
 * one gallery would defeat the point.
 *
 * [padded] exists for issue #721: file managers and gallery apps sort by
 * *string*, so `_p10` lands between `_p1` and `_p2`. Zero-padding to a fixed
 * width per work restores numeric order.
 */
data class PageNumbering(
    /** true = pages start at 1 (p1, p2, …); false = start at 0 (p0, p1, …). */
    val indexFrom1: Boolean = true,
    /** true = pad `{page}` with leading zeros so the work's pages sort as text. */
    val padded: Boolean = false,
)
