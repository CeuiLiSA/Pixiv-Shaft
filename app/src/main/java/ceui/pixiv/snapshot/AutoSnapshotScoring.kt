package ceui.pixiv.snapshot

/**
 * 自动快照行为评分。
 *
 * 把原来「详情页驻留 ≥ 60s 或 7 天内进入 ≥ 3 次」两条**硬门槛**，换成一个连续分数加唯一一条
 * 判定线（[SCORE_THRESHOLD]）：
 *
 * - 每一项都饱和成 0–100 分，不再有单项悬崖；旧的「60s」降级成曲线的半饱和点。
 * - 驻留分按作品体量归一（`页数 × [PER_PAGE_REFERENCE_MS]`），少页作品不再被一刀切 60s 卡住。
 * - 二级大图的浏览覆盖 / 每页细看 / 放大比例作为新分项，专治「多页作品刷过也算」。
 * - 组合规则是「核心项 + [SECONDARY_WEIGHT] × 辅助项之和 × 核心强度」：核心项（驻留 / 反复进入）可独立
 *   压线，辅助项（覆盖 / 细看 / 放大）只能放大核心信号，**永远无法凭空触发**。
 * - 放大是弱信号（误触双击也会置位）：除 [ZOOM_CEILING] 上限外，还按每页细看打折。
 *
 * 纯函数、无副作用、不读设置；只在 [AutoSnapshotEngine.onArtworkPageLeft] 的真离开启动点调用。
 */
internal object AutoSnapshotScoring {

    /** 每页「充分阅读」参考时长：归一化驻留与每页细看共用同一个半饱和点。 */
    internal const val PER_PAGE_REFERENCE_MS = 6_000L

    /** 页数未知（从没开过二级大图）时驻留分的回落半饱和点 = 旧的 60s 门槛。 */
    internal const val LEGACY_DWELL_REFERENCE_MS = 60_000L

    /** 「反复进入」半饱和点：窗口内进入次数减一后达到该值即 50 分（3 次进入 → 50）。 */
    internal const val REVISIT_REFERENCE = 2.0f

    /** 辅助项加权系数。 */
    internal const val SECONDARY_WEIGHT = 0.25f

    /** 放大分上限：放大是弱信号，单独最高只能贡献这么多，永远够不到 [SCORE_THRESHOLD]。 */
    internal const val ZOOM_CEILING = 20f

    /** 触发线：任一强项恰好压线的分数。 */
    internal const val SCORE_THRESHOLD = 50

    internal const val TERM_DWELL = "dwell"
    internal const val TERM_REVISIT = "revisit"
    internal const val TERM_COVERAGE = "coverage"
    internal const val TERM_ATTENTION = "attention"
    internal const val TERM_ZOOM = "zoom"

    /** 一次评分的分项与总分（都是 0–100 的整数，便于日志与展示）。 */
    internal data class ScoreBreakdown(
        val total: Int,
        val core: Int,
        val dwell: Int,
        val revisit: Int,
        val coverage: Int,
        val attention: Int,
        val zoom: Int,
        val strongest: String,
    )

    /**
     * 评分（纯函数，便于单测）。
     *
     * @param record 行为库记录；为 null 时只有驻留项可用。
     * @param dwellMs 本次真离开结算出的详情页驻留毫秒数。
     * @param now 墙钟时间，用于重新过滤 7 天窗口内的进入次数（同一页恢复时不再 recordVisit，
     *   旧访问必须在结算时重新检查窗口）。
     */
    internal fun score(
        record: AutoSnapshotBehaviorRecord?,
        dwellMs: Long,
        now: Long = System.currentTimeMillis(),
    ): ScoreBreakdown {
        // 行为库只在写入新样本时裁剪对应列表，读出来的记录里可能还躺着窗口外的二级大图会话
        // （作品一直有新访问、记录没被 prune 掉）。评分统一按 now 重新过一遍窗口。
        val windowed = record?.trimmed(now)
        val dwell = dwellScore(dwellMs, windowed?.pageCount ?: 0)
        val revisit = revisitScore(windowed, now)
        val attention = attentionScore(windowed)
        val coverage = coverageScore(windowed, attention)
        val zoom = zoomScore(windowed, attention)

        // 核心项（驻留 / 反复进入）可独立触发；辅助项只按核心强度放大 —— 实测反馈：
        // 「只看一眼就退出」若被辅助项（尤其单页 1/1 缩放打满的放大分）抬过线，会误生成。
        val core = maxOf(dwell, revisit)
        val support = SECONDARY_WEIGHT * (coverage + attention + zoom)
        val total = (core + support * (core / 100f)).coerceIn(0f, 100f)

        val strongest = listOf(
            TERM_DWELL to dwell,
            TERM_REVISIT to revisit,
            TERM_COVERAGE to coverage,
            TERM_ATTENTION to attention,
            TERM_ZOOM to zoom,
        ).maxByOrNull { it.second } ?: (TERM_DWELL to 0f)

        return ScoreBreakdown(
            total = total.toInt(),
            core = core.toInt(),
            dwell = dwell.toInt(),
            revisit = revisit.toInt(),
            coverage = coverage.toInt(),
            attention = attention.toInt(),
            zoom = zoom.toInt(),
            strongest = strongest.first,
        )
    }

    /** 驻留分：按 `页数 × 每页参考` 归一；页数未知时回落到旧的 60s。 */
    private fun dwellScore(dwellMs: Long, pageCount: Int): Float {
        val ms = dwellMs.coerceAtLeast(0L)
        if (ms <= 0L) return 0f
        val reference =
            if (pageCount > 0) pageCount * PER_PAGE_REFERENCE_MS else LEGACY_DWELL_REFERENCE_MS
        return 100f * saturate(ms.toFloat(), reference.toFloat())
    }

    /** 反复进入分：7 天窗口内进入次数减一后饱和（3 次 → 50）。 */
    private fun revisitScore(record: AutoSnapshotBehaviorRecord?, now: Long): Float {
        val visits = record?.recentVisits.orEmpty()
            .count { now - it in 0..AutoSnapshotBehaviorStore.WINDOW_MS }
        return 100f * saturate((visits - 1).coerceAtLeast(0).toFloat(), REVISIT_REFERENCE)
    }

    /** 每页细看分：最近一次二级大图会话的每页平均驻留（6s/页 → 50）。 */
    private fun attentionScore(record: AutoSnapshotBehaviorRecord?): Float {
        val pages = latestSessionPages(record) ?: return 0f
        if (pages.isEmpty()) return 0f
        val meanMs = pages.sumOf { it.ms.toDouble() }.toFloat() / pages.size
        return 100f * saturate(meanMs, PER_PAGE_REFERENCE_MS.toFloat())
    }

    /**
     * 浏览覆盖分：最近一次会话的 `看过页数 / 总页数`，再乘上每页细看 —— 只看全、没细看不算。
     */
    private fun coverageScore(record: AutoSnapshotBehaviorRecord?, attention: Float): Float {
        val session = record?.latestViewerSession ?: return 0f
        if (session.pageCount <= 0) return 0f
        val ratio = session.viewedPages.coerceIn(0, session.pageCount).toFloat() / session.pageCount
        return 100f * ratio * (attention / 100f)
    }

    /**
     * 放大分：最近一次会话里放大过的页数占比，是**弱修饰项**。
     *
     * 上限 [ZOOM_CEILING]（永远够不到触发线），且按每页细看打折 —— 缩放了却没停留，不算细看。
     */
    private fun zoomScore(record: AutoSnapshotBehaviorRecord?, attention: Float): Float {
        val pages = latestSessionPages(record) ?: return 0f
        if (pages.isEmpty()) return 0f
        val ratio = pages.count { it.zoomed }.toFloat() / pages.size
        return ZOOM_CEILING * ratio * (attention / 100f)
    }

    /** 最近一次二级大图会话的逐页样本（同一会话的样本共享同一个 at）；无会话时为 null。 */
    private fun latestSessionPages(record: AutoSnapshotBehaviorRecord?): List<AutoSnapshotViewerPageSample>? {
        val at = record?.latestViewerSession?.at ?: return null
        return record.recentViewerPages.filter { it.at == at }
    }

    private fun saturate(value: Float, reference: Float): Float =
        if (value <= 0f || reference <= 0f) 0f else value / (value + reference)
}