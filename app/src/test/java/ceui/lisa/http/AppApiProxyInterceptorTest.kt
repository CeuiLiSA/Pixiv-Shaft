package ceui.lisa.http

import ceui.lisa.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AppApiProxyInterceptor] 的纯逻辑回归：URL 改写 + 代理地址规范化。
 *
 * 拦截器的核心逻辑（[AppApiProxyInterceptor.rewrite] / [AppApiProxyInterceptor.normalizeBase]）
 * 不依赖 Android 静态状态，直接对纯函数断言。覆盖审查关注的四类边界：
 * https 强制、尾斜杠/query 边界、误填完整 PxveAPI 地址的双前缀、非法地址回退。
 *
 * scheme 相关断言按 [BuildConfig.IS_DEBUG_MODE] 分叉：Debug 构建额外放行**显式**写出的
 * `http://`（本地起代理调试用），Release 一律拒绝。**裸域名不参与这个分叉**——任何
 * buildType 下都补 `https://`，明文只能是用户显式写出来的选择。
 */
class AppApiProxyInterceptorTest {

    // ── normalizeBase：https 强制 ──────────────────────────────────────

    @Test
    fun `裸域名自动补 https 前缀`() {
        assertEquals("https://pxve.example.com", normalize("pxve.example.com"))
    }

    @Test
    fun `裸域名在 Debug 构建下同样补 https 而不是 http`() {
        // 回归：曾按 buildType 把裸域名默认成 http://，等于让 debug 包的 Authorization /
        // refresh_token 静默走明文。明文必须是用户显式写 http:// 才发生。
        assertEquals("https://pxve.example.com", normalize("pxve.example.com"))
        assertEquals("https://192.168.10.109:3021", normalize("192.168.10.109:3021"))
    }

    @Test
    fun `已带 https 前缀原样保留`() {
        assertEquals("https://pxve.example.com", normalize("https://pxve.example.com"))
    }

    @Test
    fun `显式 http 前缀 Release 拒绝 Debug 放行`() {
        val actual = normalize("http://pxve.example.com")
        if (BuildConfig.IS_DEBUG_MODE) {
            assertEquals("Debug 允许显式 http 代理，方便本地调试", "http://pxve.example.com", actual)
        } else {
            assertNull("Release 下 http:// 明文传输令牌，必须拒绝", actual)
        }
    }

    @Test
    fun `Debug 下 http 代理的 80 端口不被写成显式冒号 80`() {
        // 回归：root 端口曾恒按 https 的 443 比较，http 代理会多拼出 ":80"，
        // 和网络测试页展示 / 探测的 URL 对不上。
        if (!BuildConfig.IS_DEBUG_MODE) return
        assertEquals("http://pxve.example.com", normalize("http://pxve.example.com"))
        val original = "https://app-api.pixiv.net/v1/illust/detail?illust_id=1".toHttpUrl()
        assertEquals(
            "http://pxve.example.com/pixiv-app-api/v1/illust/detail?illust_id=1",
            AppApiProxyInterceptor.rewrite(original, "http://pxve.example.com").toString(),
        )
    }

    @Test
    fun `非 https scheme 被拒绝`() {
        assertNull(normalize("ftp://pxve.example.com"))
    }

    // ── normalizeBase：尾部斜杠 / query 边界 ───────────────────────────

    @Test
    fun `去掉末尾单个斜杠`() {
        assertEquals("https://pxve.example.com", normalize("https://pxve.example.com/"))
    }

    @Test
    fun `去掉末尾多个斜杠而不是只去一个`() {
        assertEquals("https://pxve.example.com", normalize("https://pxve.example.com///"))
    }

    @Test
    fun `保留子路径但去掉其尾斜杠`() {
        assertEquals("https://pxve.example.com/proxy", normalize("https://pxve.example.com/proxy/"))
    }

    @Test
    fun `带 query 的根地址被拒绝`() {
        assertNull("根地址带 query 会拼出非法 URL，必须拒绝", normalize("https://pxve.example.com?token=1"))
    }

    @Test
    fun `带 fragment 的根地址被拒绝`() {
        assertNull(normalize("https://pxve.example.com#sec"))
    }

    @Test
    fun `空串与纯空白被拒绝`() {
        assertNull(normalize(""))
        assertNull(normalize("   "))
        assertNull(normalize(null))
    }

    // ── rewrite：app-api / oauth 路径约定 ─────────────────────────────

    @Test
    fun `app-api 请求改写为 pixiv-app-api 前缀`() {
        val original = "https://app-api.pixiv.net/v1/illust/detail?illust_id=1".toHttpUrl()
        val rewritten = AppApiProxyInterceptor.rewrite(original, "https://pxve.example.com")

        assertEquals("https://pxve.example.com/pixiv-app-api/v1/illust/detail?illust_id=1", rewritten.toString())
    }

    @Test
    fun `oauth 请求改写为 pixiv-oauth 前缀`() {
        val original = "https://oauth.secure.pixiv.net/auth/token".toHttpUrl()
        val rewritten = AppApiProxyInterceptor.rewrite(original, "pxve.example.com")

        assertEquals("https://pxve.example.com/pixiv-oauth/auth/token", rewritten.toString())
    }

    @Test
    fun `分页 next_url 绝对地址同样被改写且保留 query`() {
        val original = "https://app-api.pixiv.net/v2/illust/follow?restrict=all&offset=30".toHttpUrl()
        val rewritten = AppApiProxyInterceptor.rewrite(original, "https://pxve.example.com")

        assertEquals(
            "https://pxve.example.com/pixiv-app-api/v2/illust/follow?restrict=all&offset=30",
            rewritten.toString(),
        )
    }

    @Test
    fun `代理地址带子路径时路径前缀保留`() {
        val original = "https://app-api.pixiv.net/v1/illust/detail?illust_id=1".toHttpUrl()
        val rewritten = AppApiProxyInterceptor.rewrite(original, "https://pxve.example.com/proxy")

        assertEquals(
            "https://pxve.example.com/proxy/pixiv-app-api/v1/illust/detail?illust_id=1",
            rewritten.toString(),
        )
    }

    @Test
    fun `图片域名不代理`() {
        val original = "https://i.pximg.net/img-master/img/2024/01/01/00/00/00/1_p0_master1200.jpg".toHttpUrl()
        assertNull("图片域名不匹配 app-api/oauth，不代理", AppApiProxyInterceptor.rewrite(original, "https://pxve.example.com"))
    }

    @Test
    fun `非 pixiv 域名不代理`() {
        val original = "https://www.pixiv.net/ajax/user/1".toHttpUrl()
        assertNull(AppApiProxyInterceptor.rewrite(original, "https://pxve.example.com"))
    }

    // ── rewrite：误填完整 PxveAPI 地址的双前缀防护 ─────────────────────

    @Test
    fun `误填完整 pixiv-app-api 地址不会双前缀`() {
        val original = "https://app-api.pixiv.net/v1/illust/detail?illust_id=1".toHttpUrl()
        val rewritten = AppApiProxyInterceptor.rewrite(original, "https://pxve.example.com/pixiv-app-api")

        assertEquals("https://pxve.example.com/pixiv-app-api/v1/illust/detail?illust_id=1", rewritten.toString())
    }

    @Test
    fun `误填完整 pixiv-oauth 地址不会双前缀`() {
        val original = "https://oauth.secure.pixiv.net/auth/token".toHttpUrl()
        val rewritten = AppApiProxyInterceptor.rewrite(original, "https://pxve.example.com/pixiv-oauth")

        assertEquals("https://pxve.example.com/pixiv-oauth/auth/token", rewritten.toString())
    }

    // ── rewrite：非法 / 空地址回退 ─────────────────────────────────────

    @Test
    fun `空代理地址返回 null`() {
        val original = "https://app-api.pixiv.net/v1/illust/detail?illust_id=1".toHttpUrl()
        assertNull(AppApiProxyInterceptor.rewrite(original, ""))
        assertNull(AppApiProxyInterceptor.rewrite(original, "  "))
        assertNull(AppApiProxyInterceptor.rewrite(original, null))
    }

    @Test
    fun `http 明文代理地址 Release 返回 null 而非改写`() {
        val original = "https://app-api.pixiv.net/v1/illust/detail?illust_id=1".toHttpUrl()
        val rewritten = AppApiProxyInterceptor.rewrite(original, "http://pxve.example.com")
        if (BuildConfig.IS_DEBUG_MODE) {
            assertEquals(
                "http://pxve.example.com/pixiv-app-api/v1/illust/detail?illust_id=1",
                rewritten.toString(),
            )
        } else {
            assertNull("http:// 必须被拒绝，避免令牌走明文", rewritten)
        }
    }

    @Test
    fun `带 query 的代理地址返回 null 而非拼出非法 URL`() {
        val original = "https://app-api.pixiv.net/v1/illust/detail?illust_id=1".toHttpUrl()
        assertNull(AppApiProxyInterceptor.rewrite(original, "https://pxve.example.com?token=1"))
    }

    private fun normalize(proxy: String?): String? = AppApiProxyInterceptor.normalizeBase(proxy)
}
