package ceui.pixiv.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutoSnapshotScoring] 的纯 JVM 测试。
 *
 * 钉住三件事：旧的 60s 变成「页数 × 6s」的归一化地板、各分项的半饱和点、以及
 * 「核心项 + 0.25 × 辅助项 × 核心强度」的组合与 50 分触发线。
 */
class AutoSnapshotScoringTest {

    private val now = 1_700_000_000_000L

    private fun record(
        pageCount: Int = 0,
        visits: List<Long> = emptyList(),
        pages: List<AutoSnapshotViewerPageSample> = emptyList(),
        sessions: List<AutoSnapshotViewerSessionSample> = emptyList(),
    ) = AutoSnapshotBehaviorRecord(
        illustId = 42L,
        pageCount = pageCount,
        recentVisits = visits,
        recentViewerPages = pages,
        recentViewerSessions = sessions,
    )

    // ---------- 驻留：归一化地板 ----------

    @Test
    fun `dwell score without page count falls back to the legacy 60s half point`() {
        val unknownPages = record(pageCount = 0)
        assertEquals(50, AutoSnapshotScoring.score(unknownPages, dwellMs = 60_000L, now = now).dwell)
        assertEquals(33, AutoSnapshotScoring.score(unknownPages, dwellMs = 30_000L, now = now).dwell)
    }

    @Test
    fun `dwell floor scales with page count`() {
        // ratio = 1 时恰好 50 分：所需驻留 = 页数 × 6s。
        val cases = listOf(1 to 6_000L, 3 to 18_000L, 10 to 60_000L, 30 to 180_000L, 100 to 600_000L)
        for ((pages, ms) in cases) {
            val breakdown = AutoSnapshotScoring.score(record(pageCount = pages), dwellMs = ms, now = now)
            assertEquals("pages=$pages", 50, breakdown.dwell)
        }
    }

    @Test
    fun `the same dwell scores higher on a few page work than on a long work`() {
        val few = AutoSnapshotScoring.score(record(pageCount = 3), dwellMs = 60_000L, now = now).dwell
        val many = AutoSnapshotScoring.score(record(pageCount = 100), dwellMs = 60_000L, now = now).dwell
        assertEquals(76, few)
        assertEquals(9, many)
    }

    // ---------- 反复进入 ----------

    @Test
    fun `three entries in window score 50`() {
        val r = record(visits = listOf(now, now - 1_000L, now - 2_000L))
        assertEquals(50, AutoSnapshotScoring.score(r, dwellMs = 0L, now = now).revisit)
    }

    @Test
    fun `revisit ignores entries outside the window and future entries`() {
        val window = AutoSnapshotBehaviorStore.WINDOW_MS
        val expired = record(visits = listOf(now - window - 1L, now - window, now))
        assertEquals(33, AutoSnapshotScoring.score(expired, 0L, now).revisit)

        val future = record(visits = listOf(now, now + 1L, now + 2L))
        assertEquals(0, AutoSnapshotScoring.score(future, 0L, now).revisit)
    }

    // ---------- 二级大图三项 ----------

    @Test
    fun `attention half point is the per-page reference`() {
        val pages = (0 until 3).map {
            AutoSnapshotViewerPageSample(at = now, page = it, ms = 6_000L, zoomed = false)
        }
        val r = record(pages = pages, sessions = listOf(AutoSnapshotViewerSessionSample(at = now, pageCount = 3, viewedPages = 3)))
        assertEquals(50, AutoSnapshotScoring.score(r, 0L, now).attention)
    }

    @Test
    fun `coverage is gated by attention`() {
        // 100 页全部翻过，但每页 0.6s：覆盖分被细看打折，不该单独拉满。
        val pages = (0 until 100).map {
            AutoSnapshotViewerPageSample(at = now, page = it, ms = 600L, zoomed = false)
        }
        val r = record(pages = pages, sessions = listOf(AutoSnapshotViewerSessionSample(at = now, pageCount = 100, viewedPages = 100)))
        val breakdown = AutoSnapshotScoring.score(r, 0L, now)
        assertEquals(9, breakdown.attention)
        assertEquals(9, breakdown.coverage)
    }

    @Test
    fun `zoom is a weak modifier capped and attenuated by attention`() {
        val pages = listOf(
            AutoSnapshotViewerPageSample(at = now, page = 0, ms = 6_000L, zoomed = true),
            AutoSnapshotViewerPageSample(at = now, page = 1, ms = 6_000L, zoomed = false),
            AutoSnapshotViewerPageSample(at = now, page = 2, ms = 6_000L, zoomed = true),
            AutoSnapshotViewerPageSample(at = now, page = 3, ms = 6_000L, zoomed = false),
        )
        val r = record(pages = pages, sessions = listOf(AutoSnapshotViewerSessionSample(at = now, pageCount = 4, viewedPages = 4)))
        // 2/4 缩放 = 上限 20 的一半 = 10，再按每页细看 50 打折 → 5。
        assertEquals(5, AutoSnapshotScoring.score(r, 0L, now).zoom)
    }

    // ---------- 组合 ----------

    @Test
    fun `strongest term alone reaches the threshold`() {
        val r = record(visits = listOf(now, now - 1_000L, now - 2_000L))
        val breakdown = AutoSnapshotScoring.score(r, dwellMs = 0L, now = now)
        assertEquals(AutoSnapshotScoring.TERM_REVISIT, breakdown.strongest)
        assertEquals(50, breakdown.total)
        assertTrue(breakdown.total >= AutoSnapshotScoring.SCORE_THRESHOLD)
    }

    @Test
    fun `secondary terms add a quarter and the total is capped at 100`() {
        val pages = (0 until 3).map {
            AutoSnapshotViewerPageSample(at = now, page = it, ms = 20_000L, zoomed = it == 0)
        }
        val r = record(
            pageCount = 3,
            visits = listOf(now, now - 1_000L),
            pages = pages,
            sessions = listOf(AutoSnapshotViewerSessionSample(at = now, pageCount = 3, viewedPages = 3)),
        )
        assertEquals(100, AutoSnapshotScoring.score(r, dwellMs = 60_000L, now = now).total)
    }

    @Test
    fun `few page work viewed carefully triggers while long work flipped through does not`() {
        val few = record(
            pageCount = 3,
            visits = listOf(now, now - 1_000L),
            pages = (0 until 3).map {
                AutoSnapshotViewerPageSample(at = now, page = it, ms = 20_000L, zoomed = it == 0)
            },
            sessions = listOf(AutoSnapshotViewerSessionSample(at = now, pageCount = 3, viewedPages = 3)),
        )
        val many = record(
            pageCount = 100,
            visits = listOf(now),
            pages = (0 until 100).map {
                AutoSnapshotViewerPageSample(at = now, page = it, ms = 600L, zoomed = false)
            },
            sessions = listOf(AutoSnapshotViewerSessionSample(at = now, pageCount = 100, viewedPages = 100)),
        )

        assertTrue(AutoSnapshotScoring.score(few, 60_000L, now).total >= AutoSnapshotScoring.SCORE_THRESHOLD)
        assertTrue(AutoSnapshotScoring.score(many, 60_000L, now).total < AutoSnapshotScoring.SCORE_THRESHOLD)
    }

    @Test
    fun `no record still allows the dwell term`() {
        assertEquals(50, AutoSnapshotScoring.score(null, dwellMs = 60_000L, now = now).total)
        assertEquals(0, AutoSnapshotScoring.score(null, dwellMs = 0L, now = now).total)
    }

    // ---------- 实测回归：扫一眼 + 缩放不该触发 ----------

    @Test
    fun `a glance on a single page work with one zoom does not trigger`() {
        // 实测 143991247：1 页作品，详情页只停 3.7s，二级大图看 1 页 1.3s 并缩放。
        val r = AutoSnapshotBehaviorRecord(
            illustId = 143991247L,
            pageCount = 1,
            recentViewerPages = listOf(
                AutoSnapshotViewerPageSample(at = now, page = 0, ms = 1_317L, zoomed = true)
            ),
            recentViewerSessions = listOf(
                AutoSnapshotViewerSessionSample(at = now, pageCount = 1, viewedPages = 1)
            ),
        )

        val breakdown = AutoSnapshotScoring.score(r, dwellMs = 3_721L, now = now)
        assertEquals(AutoSnapshotScoring.TERM_DWELL, breakdown.strongest)
        assertTrue("total=${breakdown.total}", breakdown.total < AutoSnapshotScoring.SCORE_THRESHOLD)
    }

    @Test
    fun `a glance across a multi page work with one zoom does not trigger`() {
        // 实测 147861003：3 页作品，详情页只停 3.4s，二级大图只看 1 页并缩放。
        val r = AutoSnapshotBehaviorRecord(
            illustId = 147861003L,
            pageCount = 3,
            recentViewerPages = listOf(
                AutoSnapshotViewerPageSample(at = now, page = 0, ms = 1_300L, zoomed = true)
            ),
            recentViewerSessions = listOf(
                AutoSnapshotViewerSessionSample(at = now, pageCount = 3, viewedPages = 1)
            ),
        )

        assertTrue(AutoSnapshotScoring.score(r, dwellMs = 3_430L, now = now).total < AutoSnapshotScoring.SCORE_THRESHOLD)
    }

    @Test
    fun `a genuinely long look on a short work still triggers`() {
        // 实测 150111154：2 页作品，详情页 33.5s（≈16.7s/页），两页都看过也都缩放。
        val r = AutoSnapshotBehaviorRecord(
            illustId = 150111154L,
            pageCount = 2,
            recentViewerPages = listOf(
                AutoSnapshotViewerPageSample(at = now, page = 0, ms = 16_762L, zoomed = true),
                AutoSnapshotViewerPageSample(at = now, page = 1, ms = 16_762L, zoomed = true),
            ),
            recentViewerSessions = listOf(
                AutoSnapshotViewerSessionSample(at = now, pageCount = 2, viewedPages = 2)
            ),
        )

        val breakdown = AutoSnapshotScoring.score(r, dwellMs = 33_525L, now = now)
        assertTrue("total=${breakdown.total}", breakdown.total >= AutoSnapshotScoring.SCORE_THRESHOLD)
    }
}