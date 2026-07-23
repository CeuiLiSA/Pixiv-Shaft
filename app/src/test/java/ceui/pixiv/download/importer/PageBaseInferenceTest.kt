package ceui.pixiv.download.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 启发式解析拿不到页码基准时，靠同一作品所有页的页码集合反推。
 *
 * 推错了的后果是阶段二的 `(illustId, page)` 查询整体错位一页 —— 详情页复用本地文件
 * 会拿错图，所以这里逐种形态钉死。
 */
class PageBaseInferenceTest {

    private fun hit(printedPage: Int?, pageBase: PageBase = PageBase.UNKNOWN) =
        NameMatch(illustId = 1L, printedPage = printedPage, pageBase = pageBase, source = "t")

    @Test
    fun `出现 p0 就是 0 基`() {
        val hits = listOf(hit(0), hit(1), hit(2))
        assertEquals(PageBase.ZERO, PageBaseInference.infer(hits))
        assertEquals(0, PageBaseInference.toZeroBasedOrNull(0, PageBase.ZERO))
        assertEquals(2, PageBaseInference.toZeroBasedOrNull(2, PageBase.ZERO))
    }

    @Test
    fun `只有 p1 起且没有可信声明时保持未知`() {
        val hits = listOf(hit(1), hit(2), hit(3))
        assertEquals(PageBase.UNKNOWN, PageBaseInference.infer(hits))
        assertNull(PageBaseInference.toZeroBasedOrNull(1, PageBase.UNKNOWN))
    }

    @Test
    fun `页码乱序也能推对`() {
        assertEquals(PageBase.ZERO, PageBaseInference.infer(listOf(hit(3), hit(0), hit(1))))
        assertEquals(PageBase.UNKNOWN, PageBaseInference.infer(listOf(hit(4), hit(1), hit(2))))
    }

    @Test
    fun `单图无页码时按 0 基`() {
        assertEquals(PageBase.ZERO, PageBaseInference.infer(listOf(hit(null))))
        assertEquals(0, PageBaseInference.toZeroBasedOrNull(null, PageBase.ZERO))
        assertEquals(0, PageBaseInference.toZeroBasedOrNull(null, PageBase.ONE))
    }

    @Test
    fun `只扫到中间几页时不猜基准`() {
        assertEquals(PageBase.UNKNOWN, PageBaseInference.infer(listOf(hit(3), hit(4))))
    }

    @Test
    fun `可信的 1 基声明可以换算`() {
        val hits = listOf(hit(1, PageBase.ONE), hit(2, PageBase.ONE))
        assertEquals(PageBase.ONE, PageBaseInference.infer(hits))
        assertEquals(0, PageBaseInference.toZeroBasedOrNull(1, PageBase.ONE))
        assertEquals(1, PageBaseInference.toZeroBasedOrNull(2, PageBase.ONE))
    }

    @Test
    fun `冲突的声明不建立页码映射`() {
        val hits = listOf(hit(1, PageBase.ZERO), hit(2, PageBase.ONE))
        assertEquals(PageBase.UNKNOWN, PageBaseInference.infer(hits))
        assertNull(PageBaseInference.toZeroBasedOrNull(1, PageBase.UNKNOWN))
    }
}
