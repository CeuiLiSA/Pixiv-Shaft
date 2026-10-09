package ceui.lisa.update

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * GitHub Releases Atom XML (RSS Feeds) 解析器。
 *
 * GitHub 的公开 Releases 提供 Atom 订阅（如 `https://github.com/CeuiLiSA/Pixiv-Shaft/releases.atom`），
 * 不受未鉴权 REST API 端点每小时 60 次的 Rate Limit 限制。
 *
 * 订阅中包含版本条目 `<entry>`、标签信息、发布日期与 HTML 格式更新日志。
 * 本解析器将 Atom 条目还原为统一的 [GitHubRelease]，并将 HTML 转换为 Markdown。
 */
object GitHubFeedParser {

    private val CODE_BLOCK_REGEX =
        Regex("""<pre[^>]*><code[^>]*>(.*?)</code></pre>""", RegexOption.DOT_MATCHES_ALL)
    private val HEADING_REGEX = Regex("""<h([1-6])[^>]*>(.*?)</h\1>""", RegexOption.DOT_MATCHES_ALL)
    private val LI_REGEX = Regex("""<li[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
    private val P_REGEX = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val A_REGEX =
        Regex("""<a\s+[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE_REGEX =
        Regex("""<code[^>]*>(.*?)</code>""", RegexOption.DOT_MATCHES_ALL)
    private val BOLD_REGEX =
        Regex("""<(?:strong|b)[^>]*>(.*?)</(?:strong|b)>""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCKQUOTE_REGEX =
        Regex("""<blockquote[^>]*>(.*?)</blockquote>""", RegexOption.DOT_MATCHES_ALL)
    private val HR_REGEX = Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE)
    private val ALL_TAGS_REGEX = Regex("""<[^>]+>""")
    private val EXCESS_NEWLINES_REGEX = Regex("""\n{3,}""")

    fun parse(
        inputStream: InputStream,
        owner: String = GitHubApi.OWNER,
        repo: String = GitHubApi.REPO
    ): List<GitHubRelease> {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                try {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                } catch (_: Throwable) {
                }
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(inputStream)
            parseDocument(doc, owner, repo)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parse(
        xmlContent: String,
        owner: String = GitHubApi.OWNER,
        repo: String = GitHubApi.REPO
    ): List<GitHubRelease> {
        return parse(xmlContent.byteInputStream(Charsets.UTF_8), owner, repo)
    }

    fun parseDocument(
        doc: Document,
        owner: String = GitHubApi.OWNER,
        repo: String = GitHubApi.REPO
    ): List<GitHubRelease> {
        val entryNodes = doc.getElementsByTagName("entry")
        val releases = mutableListOf<GitHubRelease>()

        for (i in 0 until entryNodes.length) {
            val entry = entryNodes.item(i) as? Element ?: continue
            val id = entry.getFirstChildText("id")
            val title = entry.getFirstChildText("title")
            val updated = entry.getFirstChildText("updated")
            val published = entry.getFirstChildText("published") ?: updated
            val content = entry.getFirstChildText("content")

            var htmlUrl: String? = null
            var tagFromLink: String? = null
            val linkNodes = entry.getElementsByTagName("link")
            for (j in 0 until linkNodes.length) {
                val link = linkNodes.item(j) as? Element ?: continue
                val href = link.getAttribute("href")
                val rel = link.getAttribute("rel")
                if (rel == "alternate" || href.contains("/releases/tag/")) {
                    htmlUrl = href
                    if (href.contains("/releases/tag/")) {
                        tagFromLink = href.substringAfterLast("/tag/").substringBefore("?")
                    }
                }
            }

            val tagName = tagFromLink
                ?: id?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                ?: title?.trim()?.takeIf { it.isNotBlank() }
                ?: continue

            val finalHtmlUrl = htmlUrl ?: "https://github.com/$owner/$repo/releases/tag/$tagName"
            val markdownBody = htmlToMarkdown(content)

            val versionName = tagName.removePrefix("v").removePrefix("V")
            val apkName = "PixShaft_${versionName}_classic.apk"
            val downloadUrl = "https://github.com/$owner/$repo/releases/download/$tagName/$apkName"
            val asset = GitHubAsset(
                name = apkName,
                size = 0L,
                downloadUrl = downloadUrl,
                contentType = "application/vnd.android.package-archive"
            )

            releases.add(
                GitHubRelease(
                    tagName = tagName,
                    name = title ?: tagName,
                    body = markdownBody,
                    publishedAt = published,
                    htmlUrl = finalHtmlUrl,
                    assets = listOf(asset)
                )
            )
        }

        return releases
    }

    fun isVersionTag(tag: String): Boolean {
        val clean = tag.removePrefix("v").removePrefix("V")
        return clean.firstOrNull()?.isDigit() == true && clean.split(".")
            .any { it.toIntOrNull() != null }
    }

    fun htmlToMarkdown(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var text = html
        text = text.replace(CODE_BLOCK_REGEX) { match ->
            "```\n${match.groupValues[1].trim()}\n```\n\n"
        }
        text = text.replace(HEADING_REGEX) { match ->
            val level = match.groupValues[1].toIntOrNull() ?: 2
            val hashes = "#".repeat(level.coerceIn(1, 4))
            "$hashes ${match.groupValues[2].trim()}\n\n"
        }
        text = text.replace(LI_REGEX) { match ->
            "- ${match.groupValues[1].trim()}\n"
        }
        text = text.replace(P_REGEX) { match ->
            "${match.groupValues[1].trim()}\n\n"
        }
        text = text.replace(BR_REGEX, "\n")
        text = text.replace(BLOCKQUOTE_REGEX) { match ->
            "> ${match.groupValues[1].trim()}\n\n"
        }
        text = text.replace(A_REGEX) { match ->
            val url = match.groupValues[1]
            val linkText = match.groupValues[2].replace(ALL_TAGS_REGEX, "").trim()
            "[$linkText]($url)"
        }
        text = text.replace(INLINE_CODE_REGEX) { match ->
            "`${match.groupValues[1].trim()}`"
        }
        text = text.replace(BOLD_REGEX) { match ->
            "**${match.groupValues[1].trim()}**"
        }
        text = text.replace(HR_REGEX, "\n---\n")
        text = text.replace(ALL_TAGS_REGEX, "")
        text = unescapeHtml(text)
        text = text.replace(EXCESS_NEWLINES_REGEX, "\n\n")
        return text.trim()
    }

    fun unescapeHtml(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
    }

    private fun Element.getFirstChildText(tagName: String): String? {
        val elements = getElementsByTagName(tagName)
        if (elements.length == 0) return null
        return elements.item(0)?.textContent?.trim()
    }
}