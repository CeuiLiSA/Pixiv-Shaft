package ceui.lisa.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GithubProxy] 的纯逻辑回归：插入规则 + 地址规范化。
 *
 * 这些用例跑在**没有 `Shaft.sSettings`** 的环境里（纯 JVM，不起 Robolectric），
 * 所以 [GithubProxy.wrap] 走的正是「不使用」那条分支 —— 也顺带证明了
 * 「设置没初始化时不能炸、必须原样返回」这条兜底。
 *
 * 各调用点的接入靠 `[GithubProxy.wrap]` 的返回值，编译器管不了「谁忘了接」，
 * 但「接上之后拼出来是什么」必须钉住：拼错一个斜杠就是 404，而且是网络层才暴露。
 */
class GithubProxyTest {

    private val github = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/rife-v4.6.zip"
    private val api = "https://api.github.com/repos/CeuiLiSA/Pixiv-Shaft/releases/latest"

    // ── 插入规则 ────────────────────────────────────────────────────

    @Test
    fun `加速地址插在原始 URL 的 https 之前`() {
        assertEquals(
            "https://gh-proxy.com/https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/rife-v4.6.zip",
            GithubProxy.insert("https://gh-proxy.com", github),
        )
    }

    @Test
    fun `api 子域同样被加速`() {
        assertEquals(
            "https://gh-proxy.com/https://api.github.com/repos/CeuiLiSA/Pixiv-Shaft/releases/latest",
            GithubProxy.insert("https://gh-proxy.com", api),
        )
    }

    @Test
    fun `裸域名与尾斜杠都会先规范化再插`() {
        val expected = "https://gh-proxy.com/https://github.com/a/b"
        assertEquals(expected, GithubProxy.insert("gh-proxy.com", "https://github.com/a/b"))
        assertEquals(expected, GithubProxy.insert("https://gh-proxy.com/", "https://github.com/a/b"))
        assertEquals(expected, GithubProxy.insert("  https://gh-proxy.com///  ", "https://github.com/a/b"))
    }

    @Test
    fun `使用内置列表中任意一个都是同一条规则`() {
        for (prefix in GithubProxy.BUILT_IN) {
            assertEquals("$prefix/$github", GithubProxy.insert(prefix, github))
        }
    }

    // ── 「不使用」与非法前缀：原样返回 ──────────────────────────────

    @Test
    fun `不使用 空串 null 都原样返回`() {
        assertEquals(github, GithubProxy.insert(GithubProxy.NONE, github))
        assertEquals(github, GithubProxy.insert("", github))
        assertEquals(github, GithubProxy.insert(null, github))
        // 纯 JVM 下 Shaft.sSettings 为 null：currentPrefix 必须兜到 NONE 而不是抛异常。
        assertEquals(GithubProxy.NONE, GithubProxy.currentPrefix())
        assertEquals(github, GithubProxy.wrap(github))
    }

    @Test
    fun `isEnabled 判断是否启用了有效代理`() {
        assertFalse(GithubProxy.isEnabled(null))
        assertFalse(GithubProxy.isEnabled(""))
        assertFalse(GithubProxy.isEnabled("   "))
        assertFalse(GithubProxy.isEnabled(GithubProxy.NONE))
        assertFalse(GithubProxy.isEnabled("ftp://gh-proxy.com"))
        assertTrue(GithubProxy.isEnabled("https://gh-proxy.com"))
        assertTrue(GithubProxy.isEnabled("gh-proxy.com"))
        assertTrue(GithubProxy.isEnabled("https://hk.gh-proxy.com/"))
        // 纯 JVM 下未注入 Settings 时，默认 currentPrefix 为空，无参 isEnabled 返回 false
        assertFalse(GithubProxy.isEnabled())
    }

    @Test
    fun `非法前缀原样返回而不是拼出脏地址`() {
        assertEquals(github, GithubProxy.insert("ftp://gh-proxy.com", github))
        assertEquals(github, GithubProxy.insert("https://gh-proxy.com?token=1", github))
        assertEquals(github, GithubProxy.insert("https://gh-proxy.com#frag", github))
    }

    // ── 不该碰的 URL ────────────────────────────────────────────────

    @Test
    fun `非 github 域名一律不插`() {
        for (url in listOf(
            "https://i.pximg.net/img/a.jpg",
            "https://app-api.pixiv.net/v1/illust/detail",
            "https://raw.githubusercontent.com/CeuiLiSA/Pixiv-Shaft/classic/README.md",
            "https://github.com.evil.test/a.zip",
        )) {
            assertEquals(url, GithubProxy.insert("https://gh-proxy.com", url))
        }
    }

    @Test
    fun `明文 http 与非 https scheme 不插`() {
        assertEquals("http://github.com/a", GithubProxy.insert("https://gh-proxy.com", "http://github.com/a"))
        assertEquals("ftp://github.com/a", GithubProxy.insert("https://gh-proxy.com", "ftp://github.com/a"))
    }

    @Test
    fun `解析失败的字符串原样返回`() {
        assertEquals("not a url", GithubProxy.insert("https://gh-proxy.com", "not a url"))
        assertEquals("", GithubProxy.insert("https://gh-proxy.com", ""))
    }

    @Test
    fun `已经插过前缀的 URL 不会被插第二次`() {
        val once = GithubProxy.insert("https://gh-proxy.com", github)
        // 幂等保护来自 host 判断：插过之后 host 是加速站，不再命中 github 域名。
        assertEquals(once, GithubProxy.insert("https://gh-proxy.com", once))
    }

    @Test
    fun `域名包含但不等于是子域的伪域名不插`() {
        // `notgithub.com` / `github.com.cn` 都不是 github.com 的子域，别被 endsWith 骗了。
        assertFalse(GithubProxy.isGithubHost("notgithub.com"))
        assertFalse(GithubProxy.isGithubHost("github.com.cn"))
        assertFalse(GithubProxy.isGithubHost("evilgithub.com"))
        assertTrue(GithubProxy.isGithubHost("github.com"))
        assertTrue(GithubProxy.isGithubHost("api.github.com"))
        assertTrue(GithubProxy.isGithubHost("release.github.com"))
    }

    // ── HttpUrl 版：不改写时给回原实例 ──────────────────────────────

    @Test
    fun `HttpUrl 版不改写时返回原实例`() {
        val url = github.toHttpUrl()
        assertSame(url, GithubProxy.wrap(url))
    }

    @Test
    fun `HttpUrl 版改写后仍是合法 URL 且能往返`() {
        // OkHttp 会保留 path 里的 `//`，所以 `https://加速站/https://github.com/...`
        // 这串插出来的 URL 能被原样解析、原样打出去（这是整个方案成立的前提）。
        val wrapped = "https://gh-proxy.com/$github".toHttpUrl()
        assertEquals("/https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/rife-v4.6.zip",
            wrapped.encodedPath)
        assertEquals("rife-v4.6.zip", wrapped.encodedPath.substringAfterLast('/'))
        assertEquals("https://gh-proxy.com/$github", wrapped.toString())
    }

    // ── 规范化与展示名 ──────────────────────────────────────────────

    @Test
    fun `规范化接受裸域名 拒绝 query 与非法 scheme`() {
        assertEquals("https://gh-proxy.com", GithubProxy.normalize("gh-proxy.com"))
        assertEquals("https://gh-proxy.com/proxy", GithubProxy.normalize("https://gh-proxy.com/proxy/"))
        assertNull(GithubProxy.normalize(""))
        assertNull(GithubProxy.normalize("   "))
        assertNull(GithubProxy.normalize(null))
        assertNull(GithubProxy.normalize("https://gh-proxy.com?x=1"))
        assertNull(GithubProxy.normalize("https://gh-proxy.com#f"))
        assertNull(GithubProxy.normalize("ftp://gh-proxy.com"))
    }

    @Test
    fun `展示名去掉 scheme`() {
        assertEquals("gh-proxy.com", GithubProxy.displayName("https://gh-proxy.com"))
        assertEquals("gh-proxy.com/proxy", GithubProxy.displayName("gh-proxy.com/proxy/"))
        assertEquals(GithubProxy.NONE, GithubProxy.displayName(null))
        assertEquals(GithubProxy.NONE, GithubProxy.displayName(""))
    }

    @Test
    fun `release 资产地址（app 真正下载的形态）插前缀后仍是合法 URL`() {
        // APK / AI 模型 / 表情包都是从 releases/download/... 下的，这条形态必须能原样打出去。
        val wrapped = GithubProxy.insert("https://gh-proxy.com", github).toHttpUrl()
        assertEquals("gh-proxy.com", wrapped.host)
        assertEquals(
            "/https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/rife-v4.6.zip",
            wrapped.encodedPath,
        )
        // 末段仍是原文件名：下载侧（如表情包的 sha256 校验）靠它取名字，不能因为插了前缀就变。
        assertEquals("rife-v4.6.zip", wrapped.encodedPath.substringAfterLast('/'))
    }
}
