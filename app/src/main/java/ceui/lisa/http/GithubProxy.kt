package ceui.lisa.http

import ceui.lisa.activities.Shaft
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * GitHub 加速地址的统一插入类。
 *
 * 规则只有一条：**把加速地址插到原始 URL 的 `https://` 之前**
 * ```
 * https://github.com/CeuiLiSA/Pixiv-Shaft/releases/latest
 *   → https://gh-proxy.com/https://github.com/CeuiLiSA/Pixiv-Shaft/releases/latest
 * ```
 * 「不使用」（空串）时**原样返回**，所以调用点不需要自己判开关 ——
 * 凡是从 GitHub 拉东西的地方（更新检查 / APK 下载 / AI 模型 / 表情包资源）
 * 一律直接 [wrap] 一次即可，别各自手拼前缀。
 *
 * 刻意只做字符串拼接，不改写 path、不解析加速站的返回：
 * 加速站（gh-proxy 一系）就是按「path 里那个完整 URL」去回源的，
 * 换任何一家同类服务都成立。
 */
object GithubProxy {

    /** 「不使用」：不插任何东西。 */
    const val NONE: String = ""

    /** 内置加速地址（无尾斜杠）。顺序即设置页弹窗里的顺序。 */
    @JvmField
    val BUILT_IN: List<String> = listOf(
        "https://gh-proxy.com",
        "https://hk.gh-proxy.com",
        "https://edgeone.gh-proxy.com",
        "https://gh.dpik.top",
    )

    private const val GITHUB_HOST = "github.com"
    private const val SCHEME = "https://"

    /** 当前设置下生效的前缀（已规范化、无尾斜杠）；「不使用」返回 [NONE]。 */
    @JvmStatic
    fun currentPrefix(): String = normalize(Shaft.sSettings?.githubProxy) ?: NONE

    /**
     * 把 [prefix] 插到 [url] 的 `https://` 之前。
     *
     * 以下三种情况**原样返回**（不抛异常）：
     *  - [prefix] 为空或非法（[normalize] 返回 null）；
     *  - [url] 的 host 既不是 `github.com`，也不是它的子域（`api.github.com` 属于子域）；
     *  - [url] 解析失败。
     *
     * 最后一条同时也是幂等保护：已经插过前缀的 URL，host 变成了加速站，不会再被插第二次。
     *
     * ⚠️ 写文档时别在 KDoc 里直接写带星号的域名通配（scheme 的两个斜杠后面紧跟星号），
     * 那三个字符里含块注释的起始符 —— Kotlin 的块注释**可嵌套**，KDoc 会被它当场劈开，
     * 后面的声明全被当成注释吞掉（编译只报 Unclosed comment，且报错行号很离谱）。
     */
    @JvmStatic
    fun insert(prefix: String?, url: String): String {
        val root = normalize(prefix) ?: return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (!parsed.isHttps || !isGithubHost(parsed.host)) return url
        // root 的尾斜杠已经被 normalize 去掉，这里必须自己补一个：加速站是按
        // `/<原始完整 URL>` 这条 path 回源的，少这一个斜杠拼出来就是
        // `https://gh-proxy.comhttps://github.com/...` 这种谁也解析不了的废地址。
        return "$root/$url"
    }

    /** 按当前设置插入：所有 GitHub 抓取点的唯一入口。 */
    @JvmStatic
    fun wrap(url: String): String = insert(currentPrefix(), url)

    /**
     * [HttpUrl] 版：不需要插入时返回**原实例**，调用方直接拿返回值用即可
     * （拦截器里可以靠 `!==` 判断有没有真改写）。
     */
    @JvmStatic
    fun wrap(url: HttpUrl): HttpUrl {
        val wrapped = wrap(url.toString())
        if (wrapped == url.toString()) return url
        return wrapped.toHttpUrlOrNull() ?: url
    }

    /**
     * 规范化加速地址，返回**无尾斜杠**的根地址；非法返回 null。
     *
     * 直接复用 PxveAPI 代理地址那套校验（[AppApiProxyInterceptor.normalizeBase]）：
     * 接受裸域名并补 `https://`、去尾斜杠、拒绝 query / fragment 与非法 scheme。
     * 两处不各写一套，是为了「什么算合法地址」只有一个事实源。
     */
    @JvmStatic
    fun normalize(raw: String?): String? = AppApiProxyInterceptor.normalizeBase(raw)

    /** 是否 `github.com` 或其子域（`api.github.com` 也算）。 */
    @JvmStatic
    fun isGithubHost(host: String): Boolean =
        host == GITHUB_HOST || host.endsWith(".$GITHUB_HOST")

    /** 展示用短名：去掉 scheme，设置页那一行不铺满整条 `https://`。 */
    @JvmStatic
    fun displayName(prefix: String?): String {
        val root = normalize(prefix) ?: return NONE
        return if (root.startsWith(SCHEME, ignoreCase = true)) root.substring(SCHEME.length) else root
    }
}
