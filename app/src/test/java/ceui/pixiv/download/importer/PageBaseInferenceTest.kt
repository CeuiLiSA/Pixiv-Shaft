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

    @Test
    fun `用户选择 1 基后补齐导入计划里的未知页码`() {
        val plan = ambiguousPlan()

        val resolved = plan.resolveAmbiguousPages(PageBase.ONE)

        assertEquals(2, plan.ambiguousPageRows)
        assertEquals(0, resolved.ambiguousPageRows)
        assertEquals(listOf(0, 1, 7), resolved.rows.map { it.zeroBasedPage })
        // 计划是不可变快照，选择页码基准不能反向污染预览阶段的数据。
        assertEquals(listOf(-2, -2, 7), plan.rows.map { it.zeroBasedPage })
    }

    @Test
    fun `用户选择 0 基时只改未知页码 已确定的行保持不变`() {
        val resolved = ambiguousPlan().resolveAmbiguousPages(PageBase.ZERO)

        assertEquals(0, resolved.ambiguousPageRows)
        assertEquals(listOf(1, 2, 7), resolved.rows.map { it.zeroBasedPage })
    }

    private fun ambiguousPlan() = DownloadImporter.ImportPlan(
        scannedFiles = 3,
        recognizedFiles = 3,
        works = 1,
        alreadyRecorded = 0,
        unrecognized = 0,
        unrecognizedSamples = emptyList(),
        rows = listOf(
            DownloadImporter.PendingRow(
                fileName = "work_123456789_p1.jpg",
                docUri = "content://downloads/p1",
                illustId = 123456789L,
                zeroBasedPage = -2,
                printedPage = 1,
                lastModified = 1L,
            ),
            DownloadImporter.PendingRow(
                fileName = "work_123456789_p2.jpg",
                docUri = "content://downloads/p2",
                illustId = 123456789L,
                zeroBasedPage = -2,
                printedPage = 2,
                lastModified = 2L,
            ),
            DownloadImporter.PendingRow(
                fileName = "work_123456789_p7.jpg",
                docUri = "content://downloads/p7",
                illustId = 123456789L,
                zeroBasedPage = 7,
                printedPage = 7,
                lastModified = 3L,
            ),
        ),
    )
}
