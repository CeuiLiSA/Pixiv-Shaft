package ceui.pixiv.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 守门测试：string_350 / string_351 是被当作 [SimpleDateFormat] pattern 使用的资源
 * （FeatureFeedFragment / MutedObjectsFeedFragment / FragmentHistoryList / HistoryV3Adapter），
 * 翻译时极易被破坏——历史上土耳其语翻译两次把 pattern 字母本身翻成了土语缩写
 * （2022 的 `Eklenme`、2026-04 的 `GG-AA SS:dd`），英文版的 `Added` 也曾长期不带引号，
 * 结果都是构造 formatter 时直接抛 Illegal pattern character、对应页面必崩。
 *
 * 这里遍历全部 locale 的 strings.xml，把这两条真丢给 SimpleDateFormat 构造一遍：
 * 以后任何翻译 PR 再把 pattern 翻坏，单测直接红，坏翻译进不了主干。
 */
class DateFormatPatternStringsTest {

    /** 被用作 SimpleDateFormat pattern 的字符串资源名。新增 pattern 资源时在这里登记。 */
    private val patternStringNames = listOf("string_350", "string_351")

    @Test
    fun `date pattern strings compile in every locale`() {
        val resDir = findResDir()
        val strings = File(resDir.path).listFiles { f ->
            f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists()
        }.orEmpty().sortedBy { it.name }
        assertTrue("找不到任何 values*/strings.xml，res 目录定位失败：$resDir", strings.isNotEmpty())

        val failures = mutableListOf<String>()
        var defaultChecked = 0
        for (valuesDir in strings) {
            val texts = parseStrings(File(valuesDir, "strings.xml"))
            for (name in patternStringNames) {
                val raw = texts[name] ?: continue // 该 locale 没翻这条 → 回落默认值，默认值另行校验
                if (valuesDir.name == "values") defaultChecked++
                val pattern = unescapeAndroidString(raw)
                try {
                    SimpleDateFormat(pattern, Locale.US)
                } catch (e: IllegalArgumentException) {
                    failures += "${valuesDir.name}/$name = \"$raw\" → ${e.message}"
                }
            }
        }
        // 默认 values/ 必须两条都在——防止资源改名后本测试静默空转。
        assertTrue("默认 values/strings.xml 里没找齐 $patternStringNames", defaultChecked == patternStringNames.size)
        assertTrue(
            "以下 locale 的日期 pattern 无法通过 SimpleDateFormat 编译（pattern 字母 yMdHm 不能翻译，" +
                "夹带的 ASCII 字面词必须用单引号包住，如 \\'Added\\'）：\n" + failures.joinToString("\n"),
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

    /** 还原 aapt 的转义语义：\' → '，\" → "，\\ → \（本测试只关心这三种）。 */
    private fun unescapeAndroidString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length && raw[i + 1] in "'\"\\") {
                sb.append(raw[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
