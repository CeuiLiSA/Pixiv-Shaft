package ceui.pixiv.download.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 启发式解析拿不到页码基准时，靠同一作品所有页的页码集合反推。
 *
 * 推错了的后果是阶段二的 `(illustId, page)` 查询整体错位一页 —— 详情页复用本地文件
 * 会拿错图，所以这里逐种形态钉死。
 */
class PageBaseInferenceTest {

    private fun hit(printedPage: Int?) =
        NameMatch(illustId = 1L, printedPage = printedPage, pageBase = PageBase.UNKNOWN, source = "t")

    @Test
    fun `出现 p0 就是 0 基`() {
        val hits = listOf(hit(0), hit(1), hit(2))
        assertEquals(PageBase.ZERO, PageBaseInference.infer(hits))
        assertEquals(0, PageBaseInference.toZeroBased(0, PageBase.ZERO))
        assertEquals(2, PageBaseInference.toZeroBased(2, PageBase.ZERO))
    }

    @Test
    fun `最小是 p1 就是 1 基`() {
        val hits = listOf(hit(1), hit(2), hit(3))
        assertEquals(PageBase.ONE, PageBaseInference.infer(hits))
        assertEquals(0, PageBaseInference.toZeroBased(1, PageBase.ONE))
        assertEquals(2, PageBaseInference.toZeroBased(3, PageBase.ONE))
    }

    @Test
    fun `页码乱序也能推对`() {
        assertEquals(PageBase.ZERO, PageBaseInference.infer(listOf(hit(3), hit(0), hit(1))))
        assertEquals(PageBase.ONE, PageBaseInference.infer(listOf(hit(4), hit(1), hit(2))))
    }

    @Test
    fun `单图无页码时按 0 基`() {
        assertEquals(PageBase.ZERO, PageBaseInference.infer(listOf(hit(null))))
        assertEquals(0, PageBaseInference.toZeroBased(null, PageBase.ZERO))
        assertEquals(0, PageBaseInference.toZeroBased(null, PageBase.ONE))
    }

    @Test
    fun `只扫到中间几页时按最小值推 不会推出负页码`() {
        // 用户只保留了 p3 p4 —— 推成 1 基，p3 落在第 2 页。宁可整体偏一页，
        // 也不能出现负数把行写坏。
        assertEquals(PageBase.ONE, PageBaseInference.infer(listOf(hit(3), hit(4))))
        assertEquals(0, PageBaseInference.toZeroBased(0, PageBase.ONE))
    }
}
