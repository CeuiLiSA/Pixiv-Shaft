package ceui.lisa.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFeedParserTest {

    private val sampleAtomXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>tag:github.com,2008:https://github.com/CeuiLiSA/Pixiv-Shaft/releases</id>
          <link type="text/html" rel="alternate" href="https://github.com/CeuiLiSA/Pixiv-Shaft/releases"/>
          <link type="application/atom+xml" rel="self" href="https://github.com/CeuiLiSA/Pixiv-Shaft/releases.atom"/>
          <title>Release notes from Pixiv-Shaft</title>
          <updated>2026-10-08T06:47:29Z</updated>
          <entry>
            <id>tag:github.com,2008:Repository/178835117/v4.9.6</id>
            <updated>2026-10-08T07:07:59Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/CeuiLiSA/Pixiv-Shaft/releases/tag/v4.9.6"/>
            <title>v4.9.6</title>
            <content type="html">&lt;p&gt;v4.9.6 更新日志&lt;/p&gt;
        &lt;p&gt;新功能&lt;/p&gt;
        &lt;ul&gt;
        &lt;li&gt;应用锁：打开或回到 App 时验证指纹&lt;/li&gt;
        &lt;li&gt;插画列表新增方格&lt;/li&gt;
        &lt;/ul&gt;
        &lt;div class="snippet-clipboard-content"&gt;&lt;pre&gt;&lt;code&gt;SHA-1: 123456&lt;/code&gt;&lt;/pre&gt;&lt;/div&gt;</content>
            <author><name>bot</name></author>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/178835117/sticker-assets</id>
            <updated>2026-09-21T07:44:06Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/CeuiLiSA/Pixiv-Shaft/releases/tag/sticker-assets"/>
            <title>Sticker assets / 贴纸资源</title>
            <content type="html">&lt;p&gt;资源包&lt;/p&gt;</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/178835117/v4.9.5</id>
            <updated>2026-09-29T04:04:17Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/CeuiLiSA/Pixiv-Shaft/releases/tag/v4.9.5"/>
            <title>v4.9.5</title>
            <content type="html">&lt;p&gt;日常维护与修复&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `parse valid atom feed returns releases`() {
        val releases = GitHubFeedParser.parse(sampleAtomXml)
        assertEquals(3, releases.size)

        val latest = releases[0]
        assertEquals("v4.9.6", latest.tagName)
        assertEquals("v4.9.6", latest.name)
        assertEquals("2026-10-08T07:07:59Z", latest.publishedAt)
        assertEquals("https://github.com/CeuiLiSA/Pixiv-Shaft/releases/tag/v4.9.6", latest.htmlUrl)

        assertNotNull(latest.assets)
        assertEquals(1, latest.assets?.size)
        val asset = latest.assets!![0]
        assertEquals("PixShaft_4.9.6_classic.apk", asset.name)
        assertEquals(
            "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.9.6/PixShaft_4.9.6_classic.apk",
            asset.downloadUrl
        )

        assertTrue(latest.body!!.contains("v4.9.6 更新日志"))
        assertTrue(latest.body!!.contains("- 应用锁：打开或回到 App 时验证指纹"))
        assertTrue(latest.body!!.contains("```\nSHA-1: 123456\n```"))
    }

    @Test
    fun `isVersionTag identifies app releases and filters asset releases`() {
        assertTrue(GitHubFeedParser.isVersionTag("v4.9.6"))
        assertTrue(GitHubFeedParser.isVersionTag("V4.9.6"))
        assertTrue(GitHubFeedParser.isVersionTag("4.9.6"))
        assertTrue(GitHubFeedParser.isVersionTag("v5.0.0-rc1"))

        assertFalse(GitHubFeedParser.isVersionTag("sticker-assets"))
        assertFalse(GitHubFeedParser.isVersionTag("sakura-1.5b-v1"))
        assertFalse(GitHubFeedParser.isVersionTag(""))
    }

    @Test
    fun `html to markdown conversion handles formatting cleanly`() {
        val html = """
            <h2>更新摘要</h2>
            <p>重要修复与优化：</p>
            <ul>
                <li>修复了<b>下载</b>失败的问题</li>
                <li>详见 <a href="https://example.com/issue/1">Issue #1</a></li>
                <li>支持 <code>inline code</code></li>
            </ul>
            <blockquote>注意：请及时更新</blockquote>
            <hr/>
            <pre><code>hash-12345</code></pre>
        """.trimIndent()

        val md = GitHubFeedParser.htmlToMarkdown(html)
        assertTrue(md.contains("## 更新摘要"))
        assertTrue(md.contains("重要修复与优化："))
        assertTrue(md.contains("- 修复了**下载**失败的问题"))
        assertTrue(md.contains("[Issue #1](https://example.com/issue/1)"))
        assertTrue(md.contains("`inline code`"))
        assertTrue(md.contains("> 注意：请及时更新"))
        assertTrue(md.contains("---"))
        assertTrue(md.contains("```\nhash-12345\n```"))
    }

    @Test
    fun `empty or malformed xml returns empty list`() {
        assertTrue(GitHubFeedParser.parse("").isEmpty())
        assertTrue(GitHubFeedParser.parse("<invalid>xml</other>").isEmpty())
    }

    @Test
    fun `AppUpdateChecker version comparison works accurately`() {
        assertTrue(AppUpdateChecker.isNewerVersion("4.9.6", "4.9.5"))
        assertTrue(AppUpdateChecker.isNewerVersion("5.0.0", "4.9.9"))
        assertTrue(AppUpdateChecker.isNewerVersion("4.10.0", "4.9.9"))
        assertFalse(AppUpdateChecker.isNewerVersion("4.9.6", "4.9.6"))
        assertFalse(AppUpdateChecker.isNewerVersion("4.9.5", "4.9.6"))
    }

    @Test
    fun `findApkAsset matches classic apk asset from parsed release`() {
        val releases = GitHubFeedParser.parse(sampleAtomXml)
        val latest = releases[0]
        val apk = AppUpdateChecker.findApkAsset(latest)
        assertNotNull(apk)
        assertEquals("PixShaft_4.9.6_classic.apk", apk?.name)
        assertEquals(
            "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.9.6/PixShaft_4.9.6_classic.apk",
            apk?.downloadUrl
        )
    }
}