package ceui.lisa.http

/**
 * Image host abstraction. Implements issue #865 (pixivcat 可否加回来).
 *
 * WIRED (issue #865). The runtime state lives here (in memory) and is applied at:
 *
 *   1. [ceui.lisa.utils.GlideUrlChild] ctor → wraps every image url with
 *      [rewrite]. This is the single choke point for on-screen loading: both
 *      the direct-Glide loads (avatars/stamps/banners/cards) and the V3
 *      imageloader (GlideImageFetcher loads a GlideUrlChild) pass through it.
 *   2. [ceui.lisa.core.Manager] download request → wraps the OkHttp request url
 *      with [rewrite] so cache-miss downloads follow the same host. The
 *      DownloadItem url stays raw everywhere else (identity / dedup / peek).
 *   3. Shaft.onCreate → hydrates mode + customHost from Settings once at
 *      startup, before the image OkHttpClient is built.
 *   4. Shaft.onCreate (OkHttp) → the directConnect SSL/DNS bypass is gated on
 *      [requiresStandardClient]; non-PIXIV modes fall back to system DNS +
 *      standard TLS (the hardcoded 210.140.139.x IPs in HttpDns and the
 *      SNI-skipping factory would break pixiv.cat / arbitrary proxies).
 *   5. FragmentSettings → picker row (Pixiv / pixiv.cat / custom URL). Because
 *      the image OkHttpClient is built once at startup and captured by Glide
 *      (same limitation the directConnect toggle has), a mode change persists
 *      to Settings and takes effect on the next app launch via hydration —
 *      the live state here is only ever set at startup, keeping url rewriting
 *      and the client consistent within a session.
 *
 * Defaults:
 *
 *   - CUSTOM accepts a full URL prefix: "https://your.proxy[/optional/path]".
 *     Trailing slash is stripped on set. The original "https://i.pximg.net"
 *     prefix is replaced wholesale; the remaining path/query is preserved.
 *     This lets a user point at a proxy mounted under a sub-path.
 *
 *   - PIXIV_CAT / PIXIV_RE / PIXIV_NL only map i.pximg.net → i.pixiv.*.
 *     These mirrors do NOT serve s.pximg.net: s.pixiv.cat/.re/.nl are NXDOMAIN
 *     and i.pixiv.* 404s on /common/... paths. So s.pximg.net URLs (comment
 *     stamps & emoji, placeholder/profile images) stay on the original host
 *     (issue #1152).
 */
object ImageHostManager {

    // Order == persisted Settings ordinal. Append new modes before CUSTOM only
    // while the feature is unshipped; once persisted, append at the end instead.
    enum class Mode { PIXIV, PIXIV_CAT, PIXIV_RE, PIXIV_NL, CUSTOM }

    private const val PXIMG_I = "i.pximg.net"
    private const val PXIMG_S = "s.pximg.net"
    private const val PIXIV_CAT_I = "i.pixiv.cat"
    // pixiv.re / pixiv.nl: pixiv.cat 的备用镜像(同 i.pximg.net 路径反代)。
    // pixiv.cat 主域名在中国大陆被墙,pixiv.re 为大陆推荐镜像(issue #865 追加)。
    private const val PIXIV_RE_I = "i.pixiv.re"
    private const val PIXIV_NL_I = "i.pixiv.nl"

    @Volatile private var mode: Mode = Mode.PIXIV
    @Volatile private var customHost: String = ""

    fun getMode(): Mode = mode
    fun setMode(value: Mode) { mode = value }

    /** Persisted-ordinal accessor for [Mode]; kept next to the enum so the
     *  Settings int (0=PIXIV, 1=PIXIV_CAT, 2=PIXIV_RE, 3=PIXIV_NL, 4=CUSTOM) has one mapping site. */
    fun getModeOrdinal(): Int = mode.ordinal

    /** Set mode from a persisted ordinal, clamping unknown/out-of-range to PIXIV. */
    fun setModeOrdinal(ordinal: Int) {
        mode = Mode.values().getOrElse(ordinal) { Mode.PIXIV }
    }

    fun getCustomHost(): String = customHost
    fun setCustomHost(value: String) { customHost = value.trim().trimEnd('/') }

    /**
     * True when the active host requires standard system DNS + TLS-with-SNI.
     * The OkHttp builder in Shaft.onCreate must consult this before installing
     * the directConnect DNS / SNI-skip / cert-bypass overrides — those are
     * pinned to Pixiv's CDN IPs and would break any other host.
     */
    fun requiresStandardClient(): Boolean = mode != Mode.PIXIV

    /**
     * Rewrites a Pixiv image URL to the currently selected host. Returns the
     * input unchanged when:
     *   - the URL is empty or not parseable as scheme://host[:port][/...]
     *   - the host is not a recognized Pixiv image domain
     *   - mode is PIXIV
     *   - mode is CUSTOM but no customHost is configured
     */
    fun rewrite(url: String): String {
        if (url.isEmpty()) return url
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val hostStart = schemeEnd + 3
        val pathStart = url.indexOf('/', hostStart).let { if (it < 0) url.length else it }
        if (pathStart <= hostStart) return url

        val hostAndPort = url.substring(hostStart, pathStart)
        val host = hostAndPort.substringBefore(':')
        if (host != PXIMG_I && host != PXIMG_S) return url

        return when (mode) {
            Mode.PIXIV -> url
            // 镜像只反代 i.pximg.net;s.pximg.net 没有对应镜像域名，保持原样(issue #1152)。
            Mode.PIXIV_CAT -> if (host == PXIMG_I) url.substring(0, hostStart) + PIXIV_CAT_I + url.substring(pathStart) else url
            Mode.PIXIV_RE -> if (host == PXIMG_I) url.substring(0, hostStart) + PIXIV_RE_I + url.substring(pathStart) else url
            Mode.PIXIV_NL -> if (host == PXIMG_I) url.substring(0, hostStart) + PIXIV_NL_I + url.substring(pathStart) else url
            Mode.CUSTOM -> {
                if (customHost.isEmpty()) url
                else customHost + url.substring(pathStart)
            }
        }
    }
}
