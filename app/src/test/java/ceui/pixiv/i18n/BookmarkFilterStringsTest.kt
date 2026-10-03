package ceui.pixiv.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 守门测试：「过滤已收藏」的文案必须在所有已翻译的 locale 里齐。
 *
 * 这十条散在两个功能块里，任何一条漏翻都不会编译报错，只会让对应语言出现「标题是译文、
 * 选项是中文」的混搭（或整行回落中文），很难在评审里被发现：
 *
 *  - 设置侧统一入口（[ceui.lisa.fragments.FragmentSettingsBrowsing] + `FilterBookmarkedDialog`）：
 *    title / rank / following / search / none / summary / dialog_hint；
 *  - 搜索筛选「其他条件」卡片（`OtherFilterSheet` + `SearchFilterV3BottomSheet.otherSummary`）：
 *    section_bookmark_filter（卡片标题，同时也是「其他条件」行的摘要）/ bookmark_filter_all /
 *    bookmark_filter_only（卡片里的两个选项）。
 *
 * 判定口径（与 [DateFormatPatternStringsTest] 一致，不强制每个 locale 都翻，但要求**成组**）：
 *  1. 默认 `values/` 必须十条全在——防止资源改名后本测试静默空转；
 *  2. 翻了同块既有 key 的 locale（搜索块看 `search_filter_v3_section_ai`、设置块看
 *     `delete_star_illust`）就必须把这一组新 key 一起翻，不允许半块翻译。
 */
class BookmarkFilterStringsTest {

    /** 设置侧统一入口的 7 条。 */
    private val settingsSideKeys = listOf(
        "filter_bookmarked_title",
        "filter_bookmarked_rank",
        "filter_bookmarked_following",
        "filter_bookmarked_search",
        "filter_bookmarked_none",
        "filter_bookmarked_summary",
        "filter_bookmarked_dialog_hint",
    )

    /** 搜索筛选「其他条件」卡片的 3 条。 */
    private val searchSideKeys = listOf(
        "search_filter_v3_section_bookmark_filter",
        "search_filter_v3_bookmark_filter_all",
        "search_filter_v3_bookmark_filter_only",
    )

    /** 各组「同块既有 key」——该 locale 翻了它，就必须把对应那组新 key 也翻齐。 */
    private val searchSideAnchor = "search_filter_v3_section_ai"
    private val settingsSideAnchor = "delete_star_illust"

    @Test
    fun `过滤已收藏的文案在全部已翻译 locale 里成组齐备`() {
        val resDir = findResDir()
        val localeDirs = File(resDir.path).listFiles { f ->
            f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists()
        }.orEmpty().sortedBy { it.name }
        assertTrue("找不到任何 values*/strings.xml，res 目录定位失败：$resDir", localeDirs.isNotEmpty())

        val failures = mutableListOf<String>()
        var defaultChecked = 0
        for (dir in localeDirs) {
            val texts = parseStrings(File(dir, "strings.xml"))
            val missing = buildList {
                if (texts.containsKey(searchSideAnchor)) {
                    addAll(searchSideKeys.filterNot { texts.containsKey(it) })
                }
                if (texts.containsKey(settingsSideAnchor)) {
                    addAll(settingsSideKeys.filterNot { texts.containsKey(it) })
                }
            }
            if (dir.name == "values") {
                defaultChecked = (searchSideKeys + settingsSideKeys).count { texts.containsKey(it) }
            }
            if (missing.isNotEmpty()) {
                failures += "${dir.name}: 缺 ${missing.joinToString(", ")}"
            }
        }

        assertTrue(
            "默认 values/strings.xml 里没找齐全部「过滤已收藏」文案（改名后本测试会静默空转）",
            defaultChecked == searchSideKeys.size + settingsSideKeys.size,
        )
        assertTrue(
            "以下 locale 翻了同块既有 key 却漏了「过滤已收藏」的文案（会出现译文与中文混搭）：\n" +
                failures.joinToString("\n"),
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
