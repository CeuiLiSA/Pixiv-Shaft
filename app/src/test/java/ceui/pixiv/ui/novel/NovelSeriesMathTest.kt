package ceui.pixiv.ui.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelSeriesMathTest {

    private val seriesCover =
        "https://i.pximg.net/c/240x480_80/novel-cover-master/img/2024/05/09/01/50/25/sci11962368_ec9a_master1200.jpg"
    private val ownCover =
        "https://i.pximg.net/c/240x480_80/novel-cover-master/img/2026/10/12/00/01/11/ci29358150_b275_master1200.jpg"

    @Test
    fun `series cover wins over a chapter's own cover`() {
        assertEquals(seriesCover, pickSeriesCoverUrl(11962368, listOf(ownCover, null, seriesCover)))
    }

    @Test
    fun `another series' cover is not this series' cover`() {
        assertEquals(ownCover, pickSeriesCoverUrl(1196236, listOf(ownCover, seriesCover)))
    }

    @Test
    fun `falls back to the first usable cover`() {
        assertEquals(ownCover, pickSeriesCoverUrl(1, listOf(null, "", ownCover)))
        assertNull(pickSeriesCoverUrl(1, listOf(null, "")))
    }

    @Test
    fun `reading duration switches to hours from one hour on`() {
        assertNull(seriesReadingDuration(0))
        assertEquals(SeriesReadingDuration.Minutes(1), seriesReadingDuration(100))
        assertEquals(SeriesReadingDuration.Minutes(59), seriesReadingDuration(29_499))
        assertEquals(SeriesReadingDuration.Hours(1), seriesReadingDuration(30_000))
        // 533,368 字 ≈ 1067 分钟 ≈ 17.8 小时
        assertEquals(SeriesReadingDuration.Hours(18), seriesReadingDuration(533_368))
    }

    private fun ch(id: Long, index: Int, percent: Int?, at: Long = 0) =
        SeriesChapterProgress(id, index, percent, at)

    @Test
    fun `never read means no target`() {
        assertNull(seriesReadTarget(listOf(ch(1, 1, null), ch(2, 2, null)), recordedNovelId = null))
    }

    @Test
    fun `recorded chapter wins over the most recent progress`() {
        val chapters = listOf(ch(1, 1, 40, at = 900), ch(2, 2, 10, at = 100))
        assertEquals(SeriesReadTarget(2, 2), seriesReadTarget(chapters, recordedNovelId = 2))
    }

    @Test
    fun `recorded chapter not loaded yet keeps its id without an index`() {
        assertEquals(SeriesReadTarget(35, null), seriesReadTarget(listOf(ch(1, 1, 100)), recordedNovelId = 35))
    }

    @Test
    fun `legacy fallback picks the most recently read chapter`() {
        val chapters = listOf(ch(1, 1, 100, at = 100), ch(2, 2, 30, at = 300), ch(3, 3, 60, at = 200))
        assertEquals(SeriesReadTarget(2, 2), seriesReadTarget(chapters, recordedNovelId = 0))
    }

    @Test
    fun `finished chapter moves on to the next one when it is known`() {
        val chapters = listOf(ch(1, 1, 100), ch(2, 2, null))
        assertEquals(SeriesReadTarget(2, 2), seriesReadTarget(chapters, recordedNovelId = 1))
        // 最后一篇读完了就停在它上面
        assertEquals(SeriesReadTarget(2, 2), seriesReadTarget(listOf(ch(1, 1, 100), ch(2, 2, 100)), 2))
    }
}
