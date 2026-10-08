package ceui.pixiv.i18n

import ceui.pixiv.download.toast.DownloadToastKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 守门测试：「下载相关提示消息」的文案必须在所有已翻译的 locale 里齐。
 *
 * 这 20 条散在设置页入口行 + 弹窗里，漏翻任何一条都不会编译报错，只会让对应语言出现
 * 「弹窗标题是译文、某几行开关还是中文」的混搭，评审时很难一眼看出来。
 *
 * 判定口径（与 [BookmarkFilterStringsTest] / [DateFormatPatternStringsTest] 一致，不强制每个
 * locale 都翻，但要求**成组**）：
 *  1. 默认 `values/` 必须 20 条全在 —— 防止资源改名后本测试静默空转；
 *  2. 翻了下载设置块既有 key（[anchor]）的 locale 就必须把这一组新 key 一起翻，不允许半块翻译。
 */
class DownloadToastStringsTest {

    /** 入口行 + 弹窗说明 + 两个分组标题。 */
    private val frameKeys = listOf(
        "download_toast_entry_title",
        "download_toast_entry_summary",
        "download_toast_dialog_hint",
        "download_toast_group_image",
        "download_toast_group_novel",
    )

    /** 15 条消息各自在弹窗里的那一行，顺序与 DownloadToastKind 的声明顺序一致。 */
    private val messageKeys = listOf(
        "download_toast_enqueued",
        "download_toast_enqueue_failed",
        "download_toast_download_done",
        "download_toast_download_failed",
        "download_toast_aria2",
        "download_toast_storage_unavailable",
        "download_toast_record_restored",
        "download_toast_low_storage_paused",
        "download_toast_bulk_enqueue",
        "download_toast_bulk_summary",
        "download_toast_novel_progress",
        "download_toast_novel_batch_result",
        "download_toast_novel_save",
        "download_toast_novel_series_export",
        "download_toast_novel_auto_download",
    )

    /** 「下载设置块」的既有 key：该 locale 翻了它，就必须把上面两组一起翻齐。 */
    private val anchor = "setting_silent_download_title"

    @Test
    fun `下载提示消息的文案在全部已翻译 locale 里成组齐备`() {
        val resDir = findResDir()
        val localeDirs = File(resDir.path).listFiles { f ->
            f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists()
        }.orEmpty().sortedBy { it.name }
        assertTrue("找不到任何 values*/strings.xml，res 目录定位失败：$resDir", localeDirs.isNotEmpty())

        val allKeys = frameKeys + messageKeys
        val failures = mutableListOf<String>()
        var defaultChecked = 0
        for (dir in localeDirs) {
            val texts = parseStrings(File(dir, "strings.xml"))
            if (dir.name == "values") {
                defaultChecked = allKeys.count { texts.containsKey(it) }
            }
            if (texts.containsKey(anchor)) {
                val missing = allKeys.filterNot { texts.containsKey(it) }
                if (missing.isNotEmpty()) {
                    failures += "${dir.name}: 缺 ${missing.joinToString(", ")}"
                }
            }
        }

        assertTrue(
            "默认 values/strings.xml 里没找齐全部「下载相关提示消息」文案（改名后本测试会静默空转）",
            defaultChecked == allKeys.size,
        )
        assertTrue(
            "以下 locale 翻了下载设置块的既有 key 却漏了「下载相关提示消息」的文案（会出现译文与中文混搭）：\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `消息行数与枚举一一对应`() {
        // 少写一条 = 弹窗里那行会回落成中文（甚至没有），多写一条 = 有文案却没有 kind 用它。
        assertEquals(
            "弹窗消息文案与 DownloadToastKind 一一对应",
            DownloadToastKind.entries.size,
            messageKeys.size,
        )
    }

    /** Gradle 单测工作目录通常是 app 模块根；兜底仓库根，两处都不在就明确报错。 */
    private fun findResDir(): File {
        return listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到 res 目录，cwd=${File(".").absolutePath}")
    }

    private fun parseStrings(xml: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val nodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            result[el.getAttribute("name")] = el.textContent
        }
        return result
    }
}