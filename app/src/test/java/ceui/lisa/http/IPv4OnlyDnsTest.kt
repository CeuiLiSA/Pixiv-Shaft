package ceui.lisa.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

/**
 * [keepIpv4IfPossible] 的行为回归。
 *
 * 这个过滤器挂在 app-api / oauth / www / comic 四条链路的 OkHttp 客户端上，跑在所有
 * 用户的所有请求前面，但它的三条分支里有两条平时根本走不到：非白名单域名（自建
 * PxveAPI 代理）和只解析出 IPv6 的网络（IPv6-only / NAT64）。这两条一旦退化，
 * 表现分别是「代理用户莫名连不上」和「IPv6-only 网络上整个 App 报 UnknownHost」，
 * 而日常开发和真机验证都是双栈网络 + 不开代理，谁都不会碰到。所以在这里钉死。
 */
class IPv4OnlyDnsTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    private val v4 = ip("210.140.92.183")
    private val v6 = ip("2606:4700::6812:2aef")

    @Test
    fun `白名单域名同时解析出 v4 v6 时只留 v4`() {
        assertEquals(
            listOf(v4),
            keepIpv4IfPossible("app-api.pixiv.net", listOf(v6, v4)),
        )
    }

    @Test
    fun `白名单域名只解析出 IPv6 时原样返回而不是空列表`() {
        // IPv6-only / NAT64 网络：合成的 AAAA 是唯一能用的地址，滤掉就等于断网。
        assertEquals(
            listOf(v6),
            keepIpv4IfPossible("www.pixiv.net", listOf(v6)),
        )
    }

    @Test
    fun `非白名单域名一律原样放行`() {
        // 用户自建 PxveAPI 代理可能只有 IPv6，或 v6 优先，不能替它做选择。
        val resolved = listOf(v6, v4)
        assertEquals(resolved, keepIpv4IfPossible("pxve.example.com", resolved))
        assertEquals(resolved, keepIpv4IfPossible("i.pximg.net", resolved))
    }

    @Test
    fun `四个 Pixiv 域名都在白名单里`() {
        assertEquals(
            setOf(
                "app-api.pixiv.net",
                "oauth.secure.pixiv.net",
                "www.pixiv.net",
                "comic.pixiv.net",
            ),
            IPV4_ONLY_HOSTS,
        )
    }
}
