package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 自动快照行为记录的纯 JVM 测试。
 *
 * 不碰 MMKV / Android 运行时；只钉住 [AutoSnapshotBehaviorRecord] 的裁剪/合并逻辑，
 * 以及 [AutoSnapshotBehaviorStore.encodeRecord] / [AutoSnapshotBehaviorStore.decodeRecord]
 * 的损坏自愈语义：任何坏数据都必须返回 null，不能抛异常。
 */
class AutoSnapshotBehaviorStoreTest {

    @Before
    fun setUp() {
        Shaft.sGson = Gson()
    }

    // ---------- recordVisit ----------

    @Test
    fun `recordVisit appends and keeps only recent visits`() {
        val now = 1_000_000L
        val record = AutoSnapshotBehaviorRecord(illustId = 42L)
            .withVisit(now = now)
            .withVisit(now = now + 1_000L)
            .withVisit(now = now + 2_000L)

        assertEquals(listOf(now + 2_000L, now + 1_000L, now), record.recentVisits)
        assertEquals(3, record.visitCount)
    }

    @Test
    fun `recordVisit drops visits outside window`() {
        val now = 10_000L
        val old = now - AutoSnapshotBehaviorStore.WINDOW_MS - 1L
        val record = AutoSnapshotBehaviorRecord(illustId = 1L)
            .withVisit(now = old)
            .withVisit(now = now)

        assertEquals(listOf(now), record.recentVisits)
        assertEquals(2, record.visitCount)
    }

    @Test
    fun `recordVisit caps recent visits`() {
        val now = 1_000_000L
        var record = AutoSnapshotBehaviorRecord(illustId = 1L)
        for (i in 0 until 20) {
            record = record.withVisit(now = now + i * 1_000L)
        }

        assertEquals(AutoSnapshotBehaviorStore.MAX_RECENT_VISITS, record.recentVisits.size)
        assertEquals(20, record.visitCount)
        assertEquals(now + 19_000L, record.recentVisits.first())
    }

    // ---------- recordDwell ----------

    @Test
    fun `recordDwell stores sample and computes accumMs`() {
        val now = 1_000_000L
        val record = AutoSnapshotBehaviorRecord(illustId = 7L)
            .withDwell(dwellMs = 60_000L, now = now)
            .withDwell(dwellMs = 30_000L, now = now + 5_000L)

        assertEquals(2, record.recentDwells.size)
        assertEquals(90_000L, record.dwellAccumMs)
        assertEquals(30_000L, record.lastDwellMs)
        assertEquals(AutoSnapshotBehaviorStore.SIGNAL_DWELL, record.lastTriggerSignal)
    }

    @Test
    fun `recordDwell trims old samples`() {
        val now = 10_000L
        val old = now - AutoSnapshotBehaviorStore.WINDOW_MS - 1L
        val record = AutoSnapshotBehaviorRecord(illustId = 1L)
            .withDwell(dwellMs = 99_000L, now = old)
            .withDwell(dwellMs = 1_000L, now = now)

        assertEquals(1, record.recentDwells.size)
        assertEquals(1_000L, record.dwellAccumMs)
    }

    // ---------- auto snapshot marker ----------

    @Test
    fun `withAutoSnapshot sets timestamp and optional signal`() {
        val record = AutoSnapshotBehaviorRecord(illustId = 1L)
            .withAutoSnapshot(123_456L, AutoSnapshotBehaviorStore.SIGNAL_REVISIT)

        assertEquals(123_456L, record.lastAutoSnapshotAt)
        assertEquals(AutoSnapshotBehaviorStore.SIGNAL_REVISIT, record.lastTriggerSignal)
    }

    @Test
    fun `newestActivityAt picks latest signal`() {
        val record = AutoSnapshotBehaviorRecord(
            illustId = 1L,
            recentVisits = listOf(100L, 200L),
            recentDwells = listOf(AutoSnapshotDwellSample(at = 300L, ms = 10L)),
            lastAutoSnapshotAt = 400L,
        )

        assertEquals(400L, record.newestActivityAt())
    }

    // ---------- encode / decode corruption safety ----------

    @Test
    fun `encode and decode roundtrip preserves fields`() {
        val original = AutoSnapshotBehaviorRecord(
            illustId = 42L,
            type = "manga",
            recentVisits = listOf(300L, 200L, 100L),
            visitCount = 3,
            lastDwellMs = 65_000L,
            recentDwells = listOf(AutoSnapshotDwellSample(at = 300L, ms = 65_000L)),
            lastAutoSnapshotAt = 400L,
            lastTriggerSignal = AutoSnapshotBehaviorStore.SIGNAL_DWELL,
        )

        val json = AutoSnapshotBehaviorStore.encodeRecord(original)
        val decoded = checkNotNull(AutoSnapshotBehaviorStore.decodeRecord(42L, json))

        assertEquals(original, decoded)
        assertEquals(65_000L, decoded.dwellAccumMs)
    }

    @Test
    fun `decodeRecord returns null for corrupt json`() {
        assertNull(AutoSnapshotBehaviorStore.decodeRecord(1L, "not-json"))
        assertNull(AutoSnapshotBehaviorStore.decodeRecord(1L, ""))
        assertNull(AutoSnapshotBehaviorStore.decodeRecord(1L, "{}"))
        assertNull(AutoSnapshotBehaviorStore.decodeRecord(1L, "{\"illustId\": 1}"))
    }

    @Test
    fun `decodeRecord rejects mismatched id`() {
        val json = AutoSnapshotBehaviorStore.encodeRecord(
            AutoSnapshotBehaviorRecord(illustId = 42L)
        )

        assertNull(AutoSnapshotBehaviorStore.decodeRecord(43L, json))
    }

    @Test
    fun `decodeRecord rejects invalid illustId`() {
        val json = AutoSnapshotBehaviorStore.encodeRecord(
            AutoSnapshotBehaviorRecord(illustId = 0L)
        )

        assertNull(AutoSnapshotBehaviorStore.decodeRecord(0L, json))
    }

    @Test
    fun `decodeRecord rejects future schema version`() {
        val json = AutoSnapshotBehaviorStore.encodeRecord(
            AutoSnapshotBehaviorRecord(illustId = 1L)
        ).replace("\"schemaVersion\":1", "\"schemaVersion\":999")

        assertNull(AutoSnapshotBehaviorStore.decodeRecord(1L, json))
    }

    @Test
    fun `decodeRecord tolerates missing optional fields`() {
        val json = """{"illustId":9,"schemaVersion":1,"recentVisits":[1,2,3],"visitCount":3}"""
        val decoded = checkNotNull(AutoSnapshotBehaviorStore.decodeRecord(9L, json))

        assertEquals(listOf(1L, 2L, 3L), decoded.recentVisits)
        assertEquals(0L, decoded.dwellAccumMs)
        assertEquals(1, decoded.withDwell(60_000L, 4L).recentDwells.size)
    }

    @Test
    fun `decoded null collections and elements cannot crash later consumers`() {
        val inputs = listOf(
            """{"illustId":9,"schemaVersion":1} """,
            """{"illustId":9,"schemaVersion":1,"recentVisits":null,"recentDwells":null}""",
            """{"illustId":9,"schemaVersion":1,"recentVisits":[null,1],"recentDwells":[null,{"at":2,"ms":3}]}""",
        )
        for (json in inputs) {
            val decoded = checkNotNull(AutoSnapshotBehaviorStore.decodeRecord(9L, json))
            decoded.withVisit(10L).withDwell(60_000L, 11L).trimmed(12L).newestActivityAt()
            decoded.dwellAccumMs
        }
    }

    // ---------- recordViewerSession ----------

    @Test
    fun `withViewerSession appends page and session samples`() {
        val now = 1_000_000L
        val record = AutoSnapshotBehaviorRecord(illustId = 5L)
            .withViewerSession(
                pages = listOf(
                    AutoSnapshotViewerPageSample(at = now, page = 0, ms = 10_000L, zoomed = true),
                    AutoSnapshotViewerPageSample(at = now, page = 1, ms = 20_000L, zoomed = false),
                ),
                pageCount = 4,
                viewedPages = 2,
                now = now,
            )

        assertEquals(2, record.recentViewerPages.size)
        assertEquals(1, record.recentViewerSessions.size)
        assertEquals(0.5f, record.lastViewerCoverage!!, 0.0001f)
        // 观测不是触发信号：不能污染 lastTriggerSignal。
        assertNull(record.lastTriggerSignal)
    }

    @Test
    fun `withViewerSession caps and drops old samples`() {
        val now = 10_000L
        val old = now - AutoSnapshotBehaviorStore.WINDOW_MS - 1L
        val record = AutoSnapshotBehaviorRecord(illustId = 1L)
            .withViewerSession(
                pages = listOf(AutoSnapshotViewerPageSample(at = old, page = 0, ms = 99L, zoomed = false)),
                pageCount = 1,
                viewedPages = 1,
                now = old,
            )
            .withViewerSession(
                pages = listOf(AutoSnapshotViewerPageSample(at = now, page = 0, ms = 5L, zoomed = true)),
                pageCount = 1,
                viewedPages = 1,
                now = now,
            )

        assertEquals(1, record.recentViewerPages.size)
        assertEquals(5L, record.recentViewerPages.first().ms)
        assertEquals(1, record.recentViewerSessions.size)
    }

    @Test
    fun `encode and decode roundtrip preserves viewer observations`() {
        val original = AutoSnapshotBehaviorRecord(
            illustId = 42L,
            recentViewerPages = listOf(
                AutoSnapshotViewerPageSample(at = 100L, page = 2, ms = 8_000L, zoomed = true)
            ),
            recentViewerSessions = listOf(
                AutoSnapshotViewerSessionSample(at = 100L, pageCount = 5, viewedPages = 3)
            ),
        )

        val decoded = checkNotNull(
            AutoSnapshotBehaviorStore.decodeRecord(42L, AutoSnapshotBehaviorStore.encodeRecord(original))
        )

        assertEquals(original, decoded)
        assertEquals(0.6f, decoded.lastViewerCoverage!!, 0.0001f)
    }

    @Test
    fun `decodeRecord normalizes viewer nulls and clamps coverage`() {
        val json =
            """{"illustId":9,"schemaVersion":1,"recentViewerPages":[null,{"at":1,"page":0,"ms":3,"zoomed":true}],"recentViewerSessions":[null,{"at":1,"pageCount":4,"viewedPages":9}]}"""

        val decoded = checkNotNull(AutoSnapshotBehaviorStore.decodeRecord(9L, json))

        assertEquals(1, decoded.recentViewerPages.size)
        assertEquals(1, decoded.recentViewerSessions.size)
        // viewedPages 超过 pageCount 时按 pageCount 收敛，覆盖率不会 >1。
        assertEquals(1f, decoded.lastViewerCoverage!!, 0.0001f)
        decoded.trimmed(2L).newestActivityAt()
    }

    // ---------- pageCount / trigger score ----------

    @Test
    fun `withPageCount keeps the last known page count`() {
        val record = AutoSnapshotBehaviorRecord(illustId = 1L)
            .withPageCount(12)
            .withPageCount(0)

        assertEquals(12, record.pageCount)
    }

    @Test
    fun `withAutoSnapshot records the trigger score`() {
        val record = AutoSnapshotBehaviorRecord(illustId = 1L)
            .withAutoSnapshot(now = 100L, signal = AutoSnapshotBehaviorStore.SIGNAL_SCORE, score = 72)

        assertEquals(72, record.lastTriggerScore)
        assertEquals(AutoSnapshotBehaviorStore.SIGNAL_SCORE, record.lastTriggerSignal)
    }

    @Test
    fun `decodeRecord clamps page count and trigger score`() {
        val json = """{"illustId":9,"schemaVersion":1,"pageCount":-3,"lastTriggerScore":999}"""

        val decoded = checkNotNull(AutoSnapshotBehaviorStore.decodeRecord(9L, json))

        assertEquals(0, decoded.pageCount)
        assertEquals(100, decoded.lastTriggerScore)
    }
}
