package ceui.pixiv.download.importer

import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.sanitize.FsSanitizer
import ceui.pixiv.download.template.DefaultTemplates
import ceui.pixiv.download.template.PageNumbering
import ceui.pixiv.download.template.SafeTemplateRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * issue #953：反向模板匹配必须能把**自己渲染出来的**文件名再解析回去。
 *
 * 这里的 round-trip 断言是整个导入功能的地基 —— 只要哪天渲染侧（模板 DSL /
 * [FsSanitizer] / [PageNumbering]）改了而 [TemplateMatcher] 没跟上，这组测试就红，
 * 不会等到用户扫出一堆"未识别"才发现。
 */
class TemplateMatcherTest {

    private fun meta(
        id: Long = 123456789L,
        title: String = "夏日祭り",
        page: Int? = 0,
        totalPages: Int = 1,
        flags: Set<Flag> = emptySet(),
    ) = ItemMeta(
        id = id,
        title = title,
        author = Author(id = 55555L, name = "藍染"),
        createdAt = Instant.parse("2024-08-15T09:32:10Z"),
        page = page,
        totalPages = totalPages,
        width = 1920,
        height = 1080,
        flags = flags,
    )

    /** 渲染出最终落盘的文件名（含 [FsSanitizer]，与下载链路完全同源）。 */
    private fun renderFilename(
        template: String,
        meta: ItemMeta,
        ext: String = "jpg",
        numbering: PageNumbering = PageNumbering(indexFrom1 = true),
    ): String {
        val raw = SafeTemplateRender.render(template, Bucket.Illust, meta, ext, numbering)
        return FsSanitizer.clean(raw).filename
    }

    private fun baseOf(numbering: PageNumbering) =
        if (numbering.indexFrom1) PageBase.ONE else PageBase.ZERO

    /**
     * 单个 [NameMatch] 上不提供 0 基换算（基准要拿整个作品判，见 [PageBaseInference]）。
     * 测试里就按"这个作品只扫到这一页"来推，等价于生产链路上的单页情形。
     */
    private fun NameMatch.zeroBased(): Int =
        requireNotNull(
            PageBaseInference.toZeroBasedOrNull(
                printedPage,
                PageBaseInference.infer(listOf(this)),
            ),
        )

    /** 用同一条模板渲染 + 解析，断言 id 和 0 基页码都能还原。 */
    private fun assertRoundTrip(
        template: String,
        meta: ItemMeta,
        expectedZeroBasedPage: Int,
        numbering: PageNumbering = PageNumbering(indexFrom1 = true),
        ext: String = "jpg",
    ) {
        val filename = renderFilename(template, meta, ext, numbering)
        val matcher = TemplateMatcher.compile(template, baseOf(numbering))
        assertNotNull("模板编译不出 matcher: $template", matcher)
        val hit = matcher!!.match(filename)
        assertNotNull("解析不出 '$filename'（模板 $template）", hit)
        assertEquals("id 还原错了（$filename）", meta.id, hit!!.illustId)
        assertEquals("页码还原错了（$filename）", expectedZeroBasedPage, hit.zeroBased())
    }

    // —— 出厂模板 ——

    @Test
    fun `默认模板 单图`() {
        assertRoundTrip(DefaultTemplates.ILLUST, meta(totalPages = 1, page = 0), 0)
    }

    @Test
    fun `默认模板 多图 1 基页码`() {
        val m = meta(totalPages = 5, page = 3)
        val filename = renderFilename(DefaultTemplates.ILLUST, m)
        assertEquals("夏日祭り_123456789_p4.jpg", filename)
        assertRoundTrip(DefaultTemplates.ILLUST, m, expectedZeroBasedPage = 3)
    }

    @Test
    fun `默认模板 多图 0 基页码`() {
        val numbering = PageNumbering(indexFrom1 = false)
        val m = meta(totalPages = 5, page = 3)
        assertEquals("夏日祭り_123456789_p3.jpg", renderFilename(DefaultTemplates.ILLUST, m, numbering = numbering))
        assertRoundTrip(DefaultTemplates.ILLUST, m, expectedZeroBasedPage = 3, numbering = numbering)
    }

    @Test
    fun `默认模板 补零页码`() {
        val numbering = PageNumbering(indexFrom1 = true, padded = true)
        val m = meta(totalPages = 12, page = 4)
        assertEquals("夏日祭り_123456789_p05.jpg", renderFilename(DefaultTemplates.ILLUST, m, numbering = numbering))
        assertRoundTrip(DefaultTemplates.ILLUST, m, expectedZeroBasedPage = 4, numbering = numbering)
    }

    // —— 标题本身带数字 / 下划线：惰性组 + 尾锚必须切在正确的位置 ——

    @Test
    fun `标题自带数字和下划线`() {
        val m = meta(title = "foo_12345", totalPages = 1, page = 0)
        val filename = renderFilename(DefaultTemplates.ILLUST, m)
        assertEquals("foo_12345_123456789.jpg", filename)
        assertRoundTrip(DefaultTemplates.ILLUST, m, expectedZeroBasedPage = 0)
    }

    @Test
    fun `标题以 p 加数字结尾 不能被当成页码`() {
        val m = meta(title = "chapter_p7", totalPages = 1, page = 0)
        assertRoundTrip(DefaultTemplates.ILLUST, m, expectedZeroBasedPage = 0)
    }

    // —— 各个预设：文件名段前面的目录（含带 `/` 的条件块）必须被正确剥掉 ——

    @Test
    fun `所有内置预设都能 round-trip`() {
        val m = meta(totalPages = 4, page = 2, flags = setOf(Flag.R18))
        for (template in presetIllustTemplates()) {
            val filename = renderFilename(template, m)
            val matcher = TemplateMatcher.compile(template, PageBase.ONE)
            assertNotNull("预设模板编译失败: $template", matcher)
            val hit = matcher!!.match(filename)
            assertNotNull("预设模板解析失败: $template → $filename", hit)
            assertEquals(template, m.id, hit!!.illustId)
            assertEquals(template, 2, hit.zeroBased())
        }
    }

    private fun presetIllustTemplates(): List<String> = listOf(
        DefaultTemplates.ILLUST,
        "Shaft/Illusts/[?R18:R18/][?AI:AI/]{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}",
        "Shaft/{title} {id}[?p>1: p{page}].{ext}",
        "Shaft/{created:yyyy}/{created:yyyy-MM}/{title} {id}[?p>1: p{page}].{ext}",
        "Shaft/{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}",
        "Shaft/{author} ({author_id})/{created:yyyy-MM}/{title} {id}[?p>1: p{page}].{ext}",
    )

    // —— 无法用来还原 id 的模板必须被拒掉，而不是编出一条永远匹配不上的正则 ——

    @Test
    fun `文件名段不含 id 的模板编译返回 null`() {
        assertNull(TemplateMatcher.compile("Shaft/{id}/{title}.{ext}", PageBase.ONE))
        assertNull(TemplateMatcher.compile("Shaft/{title}.{ext}", PageBase.ONE))
    }

    @Test
    fun `模板语法错误返回 null 而不是抛`() {
        assertNull(TemplateMatcher.compile("Shaft/{title_{id}.{ext}", PageBase.ONE))
        assertNull(TemplateMatcher.compile("Shaft/[?p>1:{id}.{ext}", PageBase.ONE))
    }

    // —— 页码基准换算 ——

    @Test
    fun `1 基模板的 p1 是第 0 页`() {
        val matcher = TemplateMatcher.compile(DefaultTemplates.ILLUST, PageBase.ONE)!!
        assertEquals(0, matcher.match("t_123456789_p1.jpg")!!.zeroBased())
        assertEquals(4, matcher.match("t_123456789_p5.jpg")!!.zeroBased())
    }

    @Test
    fun `0 基模板的 p0 是第 0 页`() {
        val matcher = TemplateMatcher.compile(DefaultTemplates.ILLUST, PageBase.ZERO)!!
        assertEquals(0, matcher.match("t_123456789_p0.jpg")!!.zeroBased())
        assertEquals(5, matcher.match("t_123456789_p5.jpg")!!.zeroBased())
    }

    @Test
    fun `基准未知时不建立页码映射`() {
        val hit = NameMatch(illustId = 1L, printedPage = 3, pageBase = PageBase.UNKNOWN, source = "x")
        val base = PageBaseInference.infer(listOf(hit))
        assertEquals(PageBase.UNKNOWN, base)
        assertNull(PageBaseInference.toZeroBasedOrNull(hit.printedPage, base))
    }

    @Test
    fun `同作品里出现 p0 时 证据压过模板声明的基准`() {
        // 候选表里同一模板会以两种基准各注册一次，谁先命中是顺序决定的随机结果。
        // 只要这个作品有 p0，就必须判成 0 基 —— 否则整本书的本地图会整体错位一页。
        val declaredOne = TemplateMatcher.compile(DefaultTemplates.ILLUST, PageBase.ONE)!!
        val hits = listOf(
            declaredOne.match("t_123456789_p0.jpg")!!,
            declaredOne.match("t_123456789_p1.jpg")!!,
            declaredOne.match("t_123456789_p2.jpg")!!,
        )
        val base = PageBaseInference.infer(hits)
        assertEquals(PageBase.ZERO, base)
        assertEquals(
            listOf(0, 1, 2),
            hits.map { PageBaseInference.toZeroBasedOrNull(it.printedPage, base) },
        )
    }
}
