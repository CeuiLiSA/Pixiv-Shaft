package ceui.pixiv.ui.pinned

import ceui.lisa.utils.SearchTypeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class PinnedExportFormatterTest {

    private val labels = PinnedExportLabels(
        tagsHeading = "置顶标签",
        usersHeading = "置顶作者",
        historyHeading = "搜索历史",
        typeLabel = { type ->
            when (type) {
                SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID -> "ID搜插画"
                SearchTypeUtil.SEARCH_TYPE_DB_USERID -> "ID搜画师"
                SearchTypeUtil.SEARCH_TYPE_DB_URL -> "网址智能跳转"
                else -> "关键词搜索"
            }
        },
    )

    @Test
    fun `single category plain text omits section header and keeps copy-friendly lines`() {
        val tags = listOf(
            PinnedTagExportRow(keyword = "風景", translatedName = "风景", isCombo = false),
            PinnedTagExportRow(keyword = "原神 胡桃", translatedName = null, isCombo = true),
            PinnedTagExportRow(keyword = "Genshin", translatedName = "Genshin", isCombo = false),
        )
        val out = PinnedExportFormatter.format(
            format = PinnedExportFormat.TEXT,
            categories = setOf(PinnedExportCategory.TAGS),
            tags = tags,
            users = emptyList(),
            history = emptyList(),
            labels = labels,
        )
        assertEquals(
            "風景  (风景)\n原神 胡桃\nGenshin",
            out,
        )
    }

    @Test
    fun `multi-category plain text includes section headings with counts`() {
        val out = PinnedExportFormatter.format(
            format = PinnedExportFormat.TEXT,
            categories = setOf(
                PinnedExportCategory.TAGS,
                PinnedExportCategory.USERS,
                PinnedExportCategory.HISTORY,
            ),
            tags = listOf(PinnedTagExportRow(keyword = "初音ミク")),
            users = listOf(
                PinnedUserExportRow(id = 27517L, name = "藤ちょこ", account = "@fuzichoco"),
                PinnedUserExportRow(id = 42L, name = "Unnamed", account = null),
            ),
            history = listOf(
                SearchHistoryExportRow(keyword = "胡桃", searchType = SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD),
                SearchHistoryExportRow(keyword = "12345678", searchType = SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID),
            ),
            labels = labels,
        )
        assertEquals(
            """
            [置顶标签 (1)]
            初音ミク

            [置顶作者 (2)]
            藤ちょこ  (@fuzichoco, UID: 27517)
            Unnamed  (UID: 42)

            [搜索历史 (2)]
            胡桃
            12345678  [ID搜插画]
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `xml output escapes special characters, strips illegal C0 chars, and includes counts`() {
        val out = PinnedExportFormatter.format(
            format = PinnedExportFormat.XML,
            categories = setOf(
                PinnedExportCategory.TAGS,
                PinnedExportCategory.USERS,
                PinnedExportCategory.HISTORY,
            ),
            tags = listOf(
                PinnedTagExportRow(
                    keyword = "A&B <tag> \"q\" 's'\u0001",
                    translatedName = "译名<1>",
                    isCombo = false,
                    searchTimeMillis = 1_700_000_000_000L,
                ),
                PinnedTagExportRow(
                    keyword = "原神 胡桃",
                    isCombo = true,
                    searchTimeMillis = 0L,
                ),
            ),
            users = listOf(PinnedUserExportRow(id = 99L, name = "A&B", account = "ab")),
            history = listOf(
                SearchHistoryExportRow(
                    keyword = "https://pixiv.net/artworks/1?a=1&b=2",
                    searchType = SearchTypeUtil.SEARCH_TYPE_DB_URL,
                    searchTimeMillis = 1_700_000_000_000L,
                ),
            ),
            labels = labels,
            exportedAtMillis = 1_700_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(out.contains("exported_at=\"2023-11-14T22:13:20Z\""))
        assertTrue(out.contains("<pinned_tags count=\"2\">"))
        assertTrue(out.contains("keyword=\"A&amp;B &lt;tag&gt; &quot;q&quot; &apos;s&apos;\""))
        assertFalse(out.contains("\u0001"))
        assertTrue(out.contains("translated_name=\"译名&lt;1&gt;\""))
        assertTrue(out.contains("combo=\"true\""))
        assertTrue(out.contains("<pinned_users count=\"1\">"))
        assertTrue(out.contains("<user id=\"99\" name=\"A&amp;B\" account=\"ab\" />"))
        assertTrue(out.contains("<search_history count=\"1\">"))
        assertTrue(out.contains("keyword=\"https://pixiv.net/artworks/1?a=1&amp;b=2\" type=\"url\""))
    }
}
