package ceui.pixiv.ui.download

import ceui.lisa.download.FileSizeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活跃下载行的「已下 / 总长」文案 —— 响应没带 `Content-Length`（chunked / 透明解压）时
 * 总长是真的不知道，那时候必须**如实写「未知大小」**，而不是把斜杠后面留空或者留一个
 * `—` 占位符：用户看到的就是"已下字节 / 什么都没有"，分不清是没渲染出来还是真不知道。
 *
 * 两件事一起钉住：
 *  1. 未知 → 带「未知大小」标签，且绝不出现悬空斜杠；
 *  2. 未知不是终局 —— 续传 / 重试的下一个响应带回 `Content-Length`（或 206 的
 *     `Content-Range` 总长）后，同一行必须换成真实大小（[formatActiveSizeText] 按入参
 *     重算、不缓存"未知"结论；把新 totalSize 写回 item 的是上游进度回调）。
 */
class ActiveSizeTextTest {

    private val unknown = "未知大小"

    /** staging 路径用 `-1`、直写路径用 `0` 表达"总长未知"——两种哨兵都要走未知分支。 */
    @Test
    fun `总长未知时如实写未知大小，不留下悬空斜杠`() {
        val cur = FileSizeUtil.formatFileSize(1536)
        for (sentinel in listOf(-1L, 0L)) {
            val text = formatActiveSizeText(1536, sentinel, unknown)
            assertEquals("total=$sentinel", "$cur / $unknown", text)
            assertFalse("不能出现悬空斜杠: $text", text.trimEnd().endsWith("/"))
        }
    }

    /** 拿到 Content-Length 后必须替换成真实大小（不能把"未知"当成终局缓存住）。 */
    @Test
    fun `总长后来变得已知时替换为真实大小`() {
        val before = formatActiveSizeText(1536, -1, unknown)
        assertTrue("未知阶段应带标签，实得：$before", before.contains(unknown))

        val after = formatActiveSizeText(1536, 4096, unknown)
        assertEquals(
            "${FileSizeUtil.formatFileSize(1536)} / ${FileSizeUtil.formatFileSize(4096)}",
            after,
        )
        assertFalse("已知总长后不该再出现未知字样：$after", after.contains(unknown))
    }

    /** 一个字节都没下来时保持占位符 —— 没有"已下"可报，谈不上总长。 */
    @Test
    fun `零字节且总长未知时只显示占位符`() {
        assertEquals("—", formatActiveSizeText(0, -1, unknown))
        assertEquals("—", formatActiveSizeText(0, 0, unknown))
    }

    /** 总长已知时与历史文案完全一致（没顺手改歪格式）。 */
    @Test
    fun `总长已知时仍是 已下 斜杠 总长`() {
        assertEquals(
            "${FileSizeUtil.formatFileSize(500)} / ${FileSizeUtil.formatFileSize(1000)}",
            formatActiveSizeText(500, 1000, unknown),
        )
    }

    /** 大字节数 + 未知总长：标签照样完整跟在斜杠后面。 */
    @Test
    fun `未知总长在大字节数下同样完整`() {
        val text = formatActiveSizeText(1_500_000, -1, unknown)
        assertTrue("应以「/ 未知大小」收尾，实得：$text", text.endsWith("/ $unknown"))
    }
}
