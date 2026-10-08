package ceui.pixiv.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 守门测试：活跃下载行的「未知大小」文案必须在所有翻过同块 key 的 locale 里齐。
 *
 * 漏翻不会编译报错，只会让对应语言回落成中文，评审里极难发现 —— 判定口径与
 * [BookmarkFilterStringsTest] / [DateFormatPatternStringsTest] 一致：不强制每个 locale
 * 都翻，但要求**成组**（翻了 `dlmgr_active_size_waiting` 就必须带上新的
 * `dlmgr_active_size_unknown`，否则同一行会出现"英文状态 + 中文大小"的混搭）。
 */
class ActiveSizeStringsTest {

    private val key = "dlmgr_active_size_unknown"

    /** 同块既有 key：翻了它就说明这一块被翻译过，新 key 不能漏。 */
    private val anchor = "dlmgr_active_size_waiting"

    @Test
    fun `未知大小文案在全部已翻译 locale 里齐备`() {
        val resDir = findResDir()
        val localeDirs = File(resDir.path).listFiles { f ->
            f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists()
        }.orEmpty().sortedBy { it.name }
        assertTrue("找不到任何 values*/strings.xml，res 目录定位失败：$resDir", localeDirs.isNotEmpty())

        val failures = mutableListOf<String>()
        var defaultPresent = false
        for (dir in localeDirs) {
            val texts = parseStrings(File(dir, "strings.xml"))
            if (dir.name == "values") defaultPresent = !texts[key].isNullOrBlank()
            if (texts.containsKey(anchor) && texts[key].isNullOrBlank()) {
                failures += "${dir.name}: 缺 $key"
            }
        }

        assertTrue("默认 values/strings.xml 里没有 $key（改名后本测试会静默空转）", defaultPresent)
        assertTrue(
            "以下 locale 翻了 $anchor 却漏了 $key（会回落成中文）：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
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
