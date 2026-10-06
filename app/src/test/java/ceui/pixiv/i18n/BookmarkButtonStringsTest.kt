package ceui.pixiv.i18n

import ceui.pixiv.ui.settings.BookmarkSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 守门测试：「作品卡片上显示收藏按钮」统一入口的文案必须在所有已翻译的 locale 里齐。
 *
 * 32 条（7 条入口 / 弹窗 + 25 个卡面名）散在入口行与弹窗两处，漏翻不会编译报错，只会让某个
 * 语言出现「标题是译文、卡面名是中文」的混搭（或整行回落中文），评审里很难发现。
 *
 * 判定口径与 [BookmarkFilterStringsTest] 一致：默认 `values/` 必须全在（防资源改名后本测试
 * 静默空转）；翻了同块既有 key（`filter_bookmarked_dialog_hint`）的 locale 就必须把这一组
 * 一起翻，不允许半块翻译。
 */
class BookmarkButtonStringsTest {

    /** 入口行与弹窗骨架的 7 条。 */
    private val entryKeys = listOf(
        "bookmark_button_title",
        "bookmark_button_dialog_hint",
        "bookmark_button_group_common",
        "bookmark_button_group_more",
        "bookmark_button_summary_all",
        "bookmark_button_summary_hidden",
        "bookmark_button_moved_hint",
    )

    /** 卡面名，与 [BookmarkSurface] 一一对应。 */
    private val surfaceKeys = listOf(
        "home_illust", "home_manga", "following", "discovery", "rank", "search", "user",
        "related", "detail_related", "my_collection", "bookmark_library", "watch_later",
        "widget", "web_discovery", "latest", "hot_works", "bookmark_rank", "view_rank",
        "daily_recommend", "wallpaper", "user_by_tag", "prime_tag", "corpus_tag",
        "nice_friend", "walkthrough",
    ).map { "bookmark_surface_$it" }

    private val allKeys = entryKeys + surfaceKeys

    /** 同块既有 key：该 locale 翻了它，就必须把这一组新 key 也翻齐。 */
    private val anchor = "filter_bookmarked_dialog_hint"

    @Test
    fun `卡面名与 BookmarkSurface 一一对应`() {
        assertEquals(
            "弹窗里的行数变了，这份文案清单要跟着改",
            BookmarkSurface.entries.size,
            surfaceKeys.size,
        )
        assertEquals(
            "有卡面名与枚举对不上",
            BookmarkSurface.entries.map { "bookmark_surface_${it.key}" }.toSet(),
            surfaceKeys.toSet(),
        )
    }

    @Test
    fun `统一入口文案在全部已翻译 locale 里成组齐备`() {
        val resDir = findResDir()
        val localeDirs = File(resDir.path).listFiles { f ->
            f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists()
        }.orEmpty().sortedBy { it.name }
        assertTrue("找不到任何 values*/strings.xml，res 目录定位失败：$resDir", localeDirs.isNotEmpty())

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
            "默认 values/strings.xml 里没找齐全部统一入口文案（改名后本测试会静默空转）",
            defaultChecked == allKeys.size,
        )
        assertTrue(
            "以下 locale 翻了同块既有 key 却漏了统一入口的文案（会出现译文与中文混搭）：\n" +
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
