package ceui.pixiv.download.importer

import ceui.lisa.download.FileCreator
import ceui.lisa.model.CustomFileNameCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #953 的真实素材：4.5.7 之前那套 cell 命名产出的文件名，以及所有模板都认不出
 * 时的启发式兜底。
 *
 * 特别注意「标题带 `-` / `,`」那几条 —— 旧版 `deleteSpecialWords` 把它们换成了 `_`，
 * 今天的 [ceui.pixiv.download.sanitize.FsSanitizer] 会保留，所以这类文件**永远**不可能
 * 靠"把模板调回旧格式"匹配上，只能靠这里的反向解析。
 */
class LegacyNameParserTest {

    private val parser = NameParser.forTemplates(LegacyNamePatterns.ALL)

    private fun parse(name: String) = parser.parse(name) ?: NameParser.heuristic(name)

    // —— 旧版默认组合：标题 + 作品ID + P数 ——

    @Test
    fun `旧版默认 多图 0 基`() {
        val hit = parse("夏日祭り_123456789_p0.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
        assertEquals(0, hit.printedPage)
    }

    @Test
    fun `旧版默认 多图 1 基`() {
        val hit = parse("夏日祭り_123456789_p3.png")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
        assertEquals(3, hit.printedPage)
    }

    @Test
    fun `旧版默认 单图无页码`() {
        val hit = parse("夏日祭り_123456789.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
    }

    // —— 旧消毒规则的痕迹：标题里的 `-` `,` `:` `*` 都变成了 `_` ——

    @Test
    fun `标题里的连字符被旧版换成下划线`() {
        // 原标题 "Re-Zero,第1话" → 旧版落盘成 "Re_Zero_第1话"
        val hit = parse("Re_Zero_第1话_98765432_p1.jpg")
        assertNotNull(hit)
        assertEquals(98765432L, hit!!.illustId)
        assertEquals(1, hit.printedPage)
    }

    @Test
    fun `标题以数字结尾`() {
        val hit = parse("東方Project2024_87654321_p2.jpg")
        assertNotNull(hit)
        assertEquals(87654321L, hit!!.illustId)
        assertEquals(2, hit.printedPage)
    }

    // —— 勾了额外 cell 的组合 ——

    @Test
    fun `带画师ID`() {
        val hit = parse("夏日祭り_123456789_p0_55555.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
    }

    @Test
    fun `带画师ID和昵称`() {
        val hit = parse("夏日祭り_123456789_p0_55555_藍染.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
    }

    @Test
    fun `带尺寸`() {
        // 旧版渲染 1920px*1080px，deleteSpecialWords 把 * 换成 _
        val hit = parse("夏日祭り_123456789_p0_1920px_1080px.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
    }

    @Test
    fun `带创作时间`() {
        val hit = parse("夏日祭り_123456789_p0_20240815_093210.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
    }

    @Test
    fun `无标题只有ID`() {
        val hit = parse("123456789_p0.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
    }

    // —— 启发式兜底 ——

    @Test
    fun `启发式 唯一数字串`() {
        val hit = NameParser.heuristic("完全没见过的命名 [123456789].webp")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
        assertEquals(PageBase.UNKNOWN, hit.pageBase)
        // 文件名里压根没有页码 → 无论基准是什么都只可能是第 0 页
        assertNull(hit.printedPage)
        assertEquals(PageBase.ZERO, PageBaseInference.infer(listOf(hit)))
    }

    @Test
    fun `启发式 整个作品的页码集合决定基准`() {
        val zeroBased = listOf("x_[123456789]_p0.webp", "x_[123456789]_p1.webp")
            .map { NameParser.heuristic(it)!! }
        assertEquals(PageBase.ZERO, PageBaseInference.infer(zeroBased))

        val oneBased = listOf("x_[123456789]_p1.webp", "x_[123456789]_p2.webp")
            .map { NameParser.heuristic(it)!! }
        assertEquals(PageBase.UNKNOWN, PageBaseInference.infer(oneBased))
    }

    @Test
    fun `启发式 尺寸里的数字不算候选`() {
        val hit = NameParser.heuristic("wallpaper_1920px_1080px_123456789.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
    }

    @Test
    fun `启发式 多个候选时宁可不认`() {
        // 作品 id 和画师 id 都在，分不清谁是谁 —— 必须报未识别，不能写脏记录
        assertNull(NameParser.heuristic("something_123456789_987654321.jpg"))
    }

    @Test
    fun `启发式 没有候选返回 null`() {
        assertNull(NameParser.heuristic("IMG_0042.jpg"))
        assertNull(NameParser.heuristic("screenshot.png"))
    }

    @Test
    fun `启发式 取到结尾页码`() {
        val hit = NameParser.heuristic("whatever_123456789_p7.jpg")
        assertNotNull(hit)
        assertEquals(7, hit!!.printedPage)
    }

    // —— 命中上浮：同一目录里的文件不该每个都把几十条候选重试一遍 ——

    @Test
    fun `命中的模板会被顶到队首`() {
        val p = NameParser.forTemplates(LegacyNamePatterns.ALL)
        val first = p.parse("夏日祭り_123456789_p0_20240815_093210.jpg")
        assertNotNull(first)
        val second = p.parse("別の絵_987654321_p1_20240815_093210.jpg")
        assertNotNull(second)
        assertEquals(987654321L, second!!.illustId)
        assertEquals(first!!.source, second.source)
    }

    @Test
    fun `旧版拖拽重排后仍按真实位置识别作品 ID`() {
        val cells = listOf(
            CustomFileNameCell("画师ID", "", FileCreator.USER_ID, true),
            CustomFileNameCell("标题", "", FileCreator.ILLUST_TITLE, true),
            CustomFileNameCell("作品ID", "", FileCreator.ILLUST_ID, true),
            CustomFileNameCell("P数", "", FileCreator.P_SIZE, true),
        )
        val reordered = NameParser.forTemplates(LegacyNamePatterns.fromCells(cells, PageBase.ONE))
        val hit = reordered.parse("55555_夏日祭り_123456789_p2.jpg")
        assertNotNull(hit)
        assertEquals(123456789L, hit!!.illustId)
        assertEquals(PageBase.ONE, hit.pageBase)
        assertEquals(1, PageBaseInference.toZeroBasedOrNull(hit.printedPage, hit.pageBase))
    }

    @Test
    fun `候选表规模不失控`() {
        // 组合爆炸会让首个文件的解析变慢，这里给个上限当护栏
        assertTrue("候选数 ${LegacyNamePatterns.ALL.size} 超预期", LegacyNamePatterns.ALL.size <= 200)
    }
}
