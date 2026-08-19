package ceui.pixiv.ui.debug

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

/**
 * [nat64EmbeddedIpv4Candidates] 的字节偏移回归。
 *
 * 网络测试页把「官方 Pixiv 域名解析出公网 IPv6」当作 DNS 污染的实锤。IPv6-only 网络上
 * DNS64 会给只有 A 记录的域名合成 AAAA，用的往往是运营商自有前缀（NSP）而不是众所周知的
 * 64:ff9b::/96 —— 那种地址在全球单播段里，光看前缀挡不住，会把一个完全正常的网络误报成污染。
 * 所以改成把嵌入的 IPv4 抠出来跟官方 CIDR 白名单比对，而这里的字节偏移一旦写错，
 * 抠出来的就是垃圾，NAT64 豁免会静默失效 —— 用 RFC 6052 §2.4 的官方向量钉死。
 */
class Nat64EmbeddedIpv4Test {

    private fun v6(s: String): Inet6Address = InetAddress.getByName(s) as Inet6Address

    // ── RFC 6052 §2.4 的六个官方向量：前缀长度不同，嵌入的都是 192.0.2.33 ──────────

    @Test
    fun `RFC 6052 六种前缀长度都能抠出 192-0-2-33`() {
        val vectors = listOf(
            "2001:db8:c000:221::",          // /32
            "2001:db8:1c0:2:21::",          // /40
            "2001:db8:122:c000:2:2100::",   // /48
            "2001:db8:122:3c0:0:221::",     // /56
            "2001:db8:122:344:c0:2:2100::", // /64
            "2001:db8:122:344::192.0.2.33", // /96
        )
        for (addr in vectors) {
            assertTrue(
                "$addr 应能抠出 192.0.2.33",
                "192.0.2.33" in nat64EmbeddedIpv4Candidates(v6(addr)),
            )
        }
    }

    // ── 真实场景：运营商自有前缀合成 Cloudflare IP，不该被判成污染 ──────────────────

    @Test
    fun `运营商自有前缀 96 合成的 Cloudflare 地址能被认出来`() {
        // 2409:8000::/96 形态的 NSP + 104.16.0.1（落在 PIXIV_CIDRS 的 104.16.0.0/13 里）。
        assertTrue("104.16.0.1" in nat64EmbeddedIpv4Candidates(v6("2409:8000::104.16.0.1")))
    }

    @Test
    fun `众所周知前缀 64-ff9b 同样走通用提取`() {
        assertTrue("210.140.92.1" in nat64EmbeddedIpv4Candidates(v6("64:ff9b::210.140.92.1")))
    }

    // ── 反例：真投毒的 IPv6 抠不出白名单里的地址 ─────────────────────────────────

    @Test
    fun `普通公网 IPv6 抠出来的候选里没有官方 IP`() {
        // GFW 投毒常见的随意 IPv6：六个候选都是无意义字节，不会撞进官方段。
        val candidates = nat64EmbeddedIpv4Candidates(v6("2001:4860:4860::8888"))
        assertTrue("104.16.0.1" !in candidates && "210.140.92.1" !in candidates)
    }
}
