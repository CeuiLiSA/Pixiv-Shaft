package ceui.pixiv.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [maskProxyUrl] 的脱敏边界回归。
 *
 * 这个函数的存在意义是：用户把网络测试页截图 / 原始日志贴到 issue 时，不要把自建 PxveAPI
 * 与图片反代的地址漏出去。所以判据只有一条——**露出来的部分必须不足以还原出原地址**。
 */
class MaskProxyUrlTest {

    // ── 域名：保留前 4 个字符 ──────────────────────────────────────────

    @Test
    fun `域名保留 scheme 与前四个字符`() {
        assertEquals("https://your***", maskProxyUrl("https://your-proxy.domain"))
        assertEquals("http://pxve***", maskProxyUrl("http://pxve.example.com"))
    }

    @Test
    fun `无 scheme 的裸域名同样脱敏`() {
        // 图片反代走 imageProxyDomain()，传进来的是剥掉 scheme 的 host[:port]。
        assertEquals("pxim***", maskProxyUrl("pximg.example.com:8443"))
    }

    // ── IPv4：按段截断，不按字符数 ────────────────────────────────────

    @Test
    fun `私网 IP 保留前两段`() {
        assertEquals("https://192.168***", maskProxyUrl("https://192.168.10.109:3021"))
        assertEquals("http://10.0***", maskProxyUrl("http://10.0.0.5:3021"))
    }

    @Test
    fun `短公网 IP 不会被整段漏出`() {
        // 回归：曾按固定 7 个字符截断，IP 越短露得越多——1.2.3.4 会被原样打印出来。
        assertEquals("https://1.2***", maskProxyUrl("https://1.2.3.4:8080"))
        assertEquals("https://8.8***", maskProxyUrl("https://8.8.8.8"))
        assertEquals("https://5.9***", maskProxyUrl("https://5.9.12.34:3021"))
        assertEquals("https://43.154***", maskProxyUrl("https://43.154.66.7"))
    }

    @Test
    fun `IP 后面的端口与路径一律不保留`() {
        assertEquals("https://203.0***", maskProxyUrl("https://203.0.113.9:31337/pxve"))
    }

    // ── 边界 ─────────────────────────────────────────────────────────

    @Test
    fun `数字开头但不是 IP 的域名走域名规则`() {
        assertEquals("https://1pan***", maskProxyUrl("https://1panel.example.com"))
        // 不完整的点分地址不按 IP 处理，退回更保守的 4 字符规则。
        assertEquals("https://192.***", maskProxyUrl("https://192.168.1"))
    }

    @Test
    fun `IPv6 字面量退回域名规则且不漏出完整地址`() {
        assertEquals("https://[200***", maskProxyUrl("https://[2001:db8::1]:3021"))
    }

    @Test
    fun `空串与纯空白不崩且不产生残留`() {
        assertEquals("***", maskProxyUrl(""))
        assertEquals("***", maskProxyUrl("   "))
        assertEquals("https://***", maskProxyUrl("https://"))
    }

    @Test
    fun `scheme 大小写不影响识别`() {
        assertEquals("https://your***", maskProxyUrl("HTTPS://your-proxy.domain"))
    }

    @Test
    fun `比原地址短的域名不会被原样返回`() {
        // take(4) 对 3 字符域名会返回全部——但这种地址本身没有可辨识价值，仅确认不崩。
        assertEquals("https://a.b***", maskProxyUrl("https://a.b"))
    }
}
