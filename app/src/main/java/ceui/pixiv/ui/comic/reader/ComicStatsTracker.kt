package ceui.pixiv.ui.comic.reader

/**
 * 把会话计时 / 翻页计数从 Fragment 抽出。Fragment 只在 lifecycle hook 里调
 * [start] / [recordFlip] / [flush]，写库由 [ComicStatsRepository] 负责。
 */
class ComicStatsTracker(
    private val illustId: Long,
    private val repo: ComicStatsRepository,
) {
    private var sessionStartMs: Long = 0L
    private var sessionFlips: Int = 0

    fun start() {
        sessionStartMs = System.currentTimeMillis()
    }

    fun recordFlip() {
        sessionFlips++
    }

    fun flush(currentPage: Int, totalPages: Int) {
        val now = System.currentTimeMillis()
        val duration = (now - sessionStartMs).coerceAtLeast(0L)
        val flips = sessionFlips
        sessionFlips = 0
        sessionStartMs = now
        if (duration <= 0L && flips <= 0) return
        val completed = totalPages > 0 && currentPage >= totalPages - 1
        repo.flushSession(illustId, currentPage, totalPages, duration, flips, completed)
    }
}
