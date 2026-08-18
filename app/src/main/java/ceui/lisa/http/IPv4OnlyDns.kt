package ceui.lisa.http

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 公用的 IPv4-only DNS 工具类（OkHttp [Dns]）。
 *
 * Pixiv 的 app-api / oauth / www 网页 ajax / comic 域名在官方 DNS 里以 IPv4 为主，
 * 系统 DNS 返回 IPv6 时多为污染，会让 OkHttp 多出一条毫无意义的 connect 尝试。
 * 这里只对已知的 Pixiv API / 网页域名过滤 IPv6，其它域名（尤其是用户自建
 * PxveAPI 代理域名）原样放行——代理可能有合法 IPv6，不能一刀切。
 *
 * 安全回退：如果系统 DNS 只返回 IPv6（例如 IPv6-only/NAT64 环境），保留原结果，
 * 不让 OkHttp 拿到空地址列表而引入新的“无地址”语义。
 */
object IPv4OnlyDns : Dns {

    private val IPV4_ONLY_HOSTS = setOf(
        "app-api.pixiv.net",
        "oauth.secure.pixiv.net",
        "www.pixiv.net",
        "comic.pixiv.net",
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        if (hostname !in IPV4_ONLY_HOSTS) return all

        val ipv4 = all.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) ipv4 else all
    }
}
