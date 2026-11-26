package ceui.pixiv.ui.pinned

import ceui.lisa.utils.SearchTypeUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 「我置顶的内容」列表导出格式（issue #1399）。 */
enum class PinnedExportFormat(val extension: String, val mimeType: String) {
    TEXT("txt", "text/plain"),
    XML("xml", "application/xml"),
}

/** 可选导出分类；顺序与页面 Tab 一致。 */
enum class PinnedExportCategory {
    TAGS,
    USERS,
    HISTORY,
}

data class PinnedTagExportRow(
    val keyword: String,
    val translatedName: String? = null,
    val isCombo: Boolean = false,
    val searchTimeMillis: Long = 0L,
)

data class PinnedUserExportRow(
    val id: Long,
    val name: String,
    val account: String? = null,
)

data class SearchHistoryExportRow(
    val keyword: String,
    val searchType: Int,
    val searchTimeMillis: Long = 0L,
)

data class PinnedExportLabels(
    val tagsHeading: String,
    val usersHeading: String,
    val historyHeading: String,
    val typeLabel: (Int) -> String,
)

/**
 * 纯函数导出格式化器：支持纯文本（方便直接查阅与复制到 App 搜索）与标准 UTF-8 XML。
 * 不依赖 Android Context，便于 JVM 单元测试覆盖转义、编码和边界情况。
 */
internal object PinnedExportFormatter {

    private val WHITESPACE = Regex("\\s+")

    fun format(
        format: PinnedExportFormat,
        categories: Set<PinnedExportCategory>,
        tags: List<PinnedTagExportRow>,
        users: List<PinnedUserExportRow>,
        history: List<SearchHistoryExportRow>,
        labels: PinnedExportLabels,
        exportedAtMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String = when (format) {
        PinnedExportFormat.TEXT -> formatText(categories, tags, users, history, labels)
        PinnedExportFormat.XML -> formatXml(categories, tags, users, history, exportedAtMillis, timeZone)
    }

    private fun formatText(
        categories: Set<PinnedExportCategory>,
        tags: List<PinnedTagExportRow>,
        users: List<PinnedUserExportRow>,
        history: List<SearchHistoryExportRow>,
        labels: PinnedExportLabels,
    ): String {
        val sections = ArrayList<String>(3)
        val includeHeaders = categories.size > 1

        if (PinnedExportCategory.TAGS in categories && tags.isNotEmpty()) {
            sections += buildString {
                if (includeHeaders) {
                    append('[').append(labels.tagsHeading).append(" (").append(tags.size).append(")]\n")
                }
                tags.forEachIndexed { index, row ->
                    if (index > 0) append('\n')
                    val keyword = row.keyword.oneLine()
                    val translated = row.translatedName?.oneLine().orEmpty()
                    append(keyword)
                    if (!row.isCombo && translated.isNotEmpty() && translated != keyword) {
                        append("  (").append(translated).append(')')
                    }
                }
            }
        }

        if (PinnedExportCategory.USERS in categories && users.isNotEmpty()) {
            sections += buildString {
                if (includeHeaders) {
                    append('[').append(labels.usersHeading).append(" (").append(users.size).append(")]\n")
                }
                users.forEachIndexed { index, row ->
                    if (index > 0) append('\n')
                    val name = row.name.oneLine()
                    val account = row.account?.oneLine()?.removePrefix("@").orEmpty()
                    append(name)
                    if (account.isNotEmpty()) {
                        append("  (@").append(account).append(", UID: ").append(row.id).append(')')
                    } else {
                        append("  (UID: ").append(row.id).append(')')
                    }
                }
            }
        }

        if (PinnedExportCategory.HISTORY in categories && history.isNotEmpty()) {
            sections += buildString {
                if (includeHeaders) {
                    append('[').append(labels.historyHeading).append(" (").append(history.size).append(")]\n")
                }
                history.forEachIndexed { index, row ->
                    if (index > 0) append('\n')
                    val keyword = row.keyword.oneLine()
                    append(keyword)
                    if (row.searchType != SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD) {
                        val typeText = labels.typeLabel(row.searchType).oneLine()
                        if (typeText.isNotEmpty()) {
                            append("  [").append(typeText).append(']')
                        }
                    }
                }
            }
        }

        return sections.joinToString("\n\n")
    }

    private fun formatXml(
        categories: Set<PinnedExportCategory>,
        tags: List<PinnedTagExportRow>,
        users: List<PinnedUserExportRow>,
        history: List<SearchHistoryExportRow>,
        exportedAtMillis: Long,
        timeZone: TimeZone,
    ): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            this.timeZone = timeZone
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<pixiv_shaft_export exported_at=\"")
            .append(escapeXml(sdf.format(Date(exportedAtMillis))))
            .append("\">\n")

        if (PinnedExportCategory.TAGS in categories) {
            sb.append("  <pinned_tags count=\"").append(tags.size).append("\">\n")
            tags.forEach { row ->
                sb.append("    <tag keyword=\"").append(escapeXml(row.keyword)).append('"')
                val translated = row.translatedName?.takeIf { it.isNotBlank() && it != row.keyword }
                if (!row.isCombo && translated != null) {
                    sb.append(" translated_name=\"").append(escapeXml(translated)).append('"')
                }
                if (row.isCombo) {
                    sb.append(" combo=\"true\"")
                }
                if (row.searchTimeMillis > 0L) {
                    sb.append(" updated_at=\"").append(escapeXml(sdf.format(Date(row.searchTimeMillis)))).append('"')
                }
                sb.append(" />\n")
            }
            sb.append("  </pinned_tags>\n")
        }

        if (PinnedExportCategory.USERS in categories) {
            sb.append("  <pinned_users count=\"").append(users.size).append("\">\n")
            users.forEach { row ->
                sb.append("    <user id=\"").append(row.id).append('"')
                sb.append(" name=\"").append(escapeXml(row.name)).append('"')
                val account = row.account?.trim()?.removePrefix("@").orEmpty()
                if (account.isNotEmpty()) {
                    sb.append(" account=\"").append(escapeXml(account)).append('"')
                }
                sb.append(" />\n")
            }
            sb.append("  </pinned_users>\n")
        }

        if (PinnedExportCategory.HISTORY in categories) {
            sb.append("  <search_history count=\"").append(history.size).append("\">\n")
            history.forEach { row ->
                sb.append("    <entry keyword=\"").append(escapeXml(row.keyword)).append('"')
                sb.append(" type=\"").append(xmlSearchTypeKey(row.searchType)).append('"')
                if (row.searchTimeMillis > 0L) {
                    sb.append(" searched_at=\"").append(escapeXml(sdf.format(Date(row.searchTimeMillis)))).append('"')
                }
                sb.append(" />\n")
            }
            sb.append("  </search_history>\n")
        }

        sb.append("</pixiv_shaft_export>\n")
        return sb.toString()
    }

    internal fun xmlSearchTypeKey(searchType: Int): String = when (searchType) {
        SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD -> "keyword"
        SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID -> "illust_id"
        SearchTypeUtil.SEARCH_TYPE_DB_USERKEYWORD -> "user_keyword"
        SearchTypeUtil.SEARCH_TYPE_DB_USERID -> "user_id"
        SearchTypeUtil.SEARCH_TYPE_DB_NOVELID -> "novel_id"
        SearchTypeUtil.SEARCH_TYPE_DB_URL -> "url"
        else -> "other"
    }

    internal fun escapeXml(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\t', '\n', '\r' -> sb.append(ch)
                else -> {
                    // XML 1.0 forbids C0 control characters outside \t, \n, \r.
                    if (ch.code >= 0x20) sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun String.oneLine(): String = trim().replace(WHITESPACE, " ")
}
