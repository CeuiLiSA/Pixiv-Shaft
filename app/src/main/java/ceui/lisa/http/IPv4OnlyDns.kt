package ceui.lisa.http

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 已知在官方 DNS 里只有 A 记录的 Pixiv 域名。其它域名（尤其是用户自建 PxveAPI
 * 代理域名）不在此列——代理可能有合法 IPv6，不能一刀切。
 */
internal val IPV4_ONLY_HOSTS = setOf(
    "app-api.pixiv.net",
    "oauth.secure.pixiv.net",
    "www.pixiv.net",
    "comic.pixiv.net",
)

/**
 * [IPv4OnlyDns] 的纯过滤部分，抽成顶层函数是为了能被单测钉死——尤其是
 * 「只解析出 IPv6 时必须原样返回」这条兜底：它一旦被改成返回空列表，
 * IPv6-only / NAT64 网络上所有 Pixiv 请求都会变成 UnknownHost，而这种网络
 * 在日常开发和真机验证里都碰不到，退化不会有任何人察觉。
 */
internal fun keepIpv4IfPossible(hostname: String, resolved: List<InetAddress>): List<InetAddress> {
    if (hostname !in IPV4_ONLY_HOSTS) return resolved

    val ipv4 = resolved.filterIsInstance<Inet4Address>()
    return if (ipv4.isNotEmpty()) ipv4 else resolved
}

/**
 * 公用的 IPv4-only DNS 工具类（OkHttp [Dns]）。
 *
 * Pixiv 的 app-api / oauth / www 网页 ajax / comic 域名在官方 DNS 里以 IPv4 为主，
 * 系统 DNS 返回 IPv6 时多为污染，会让 OkHttp 多出一条毫无意义的 connect 尝试。
 * 这里只对 [IPV4_ONLY_HOSTS] 过滤 IPv6，其它域名原样放行。
 *
 * 安全回退：如果系统 DNS 只返回 IPv6（例如 IPv6-only/NAT64 环境），保留原结果，
 * 不让 OkHttp 拿到空地址列表而引入新的“无地址”语义。
 */
object IPv4OnlyDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> =
        keepIpv4IfPossible(hostname, Dns.SYSTEM.lookup(hostname))
}
