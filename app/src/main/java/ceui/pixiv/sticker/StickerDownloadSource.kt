package ceui.pixiv.sticker

import ceui.lisa.http.GithubProxy
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Content-addressed release assets; the legacy catalog URL is never downloaded. */
internal object StickerDownloadSource {
    const val RELEASE_BASE = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/sticker-assets/"

    /** GitHub 把公开 release 资产 302 到它自己的短时签名 CDN，这一跳必须放行。 */
    private const val RELEASE_CDN_HOST = "release-assets.githubusercontent.com"

    private val digest = Regex("[a-f0-9]{64}")

    fun url(pkg: StickerPackage): String {
        require(digest.matches(pkg.sha256)) { "Missing sticker ZIP checksum" }
        // 加速地址在这里插一次（「不使用」时原样返回）：插入后整串是
        // <加速站>/https://github.com/... ，[allows] 按同一套规则校验它。
        return GithubProxy.wrap("$RELEASE_BASE${pkg.sha256}.zip")
    }

    fun allows(url: HttpUrl): Boolean {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null) return false
        if (url.host == RELEASE_CDN_HOST) return true
        // 直连与「已插加速前缀」两种形态共用同一套校验：末段必须是内容寻址的 <sha256>.zip，
        // 且整串 URL 必须**正好等于**用当前加速前缀包出来的那条 —— 于是既不会因为前缀而误杀，
        // 也不会有别的主机靠 path 里塞一个 GitHub 地址混成合法下载源。
        val name = url.encodedPath.substringAfterLast('/')
        if (!name.endsWith(".zip") || !digest.matches(name.removeSuffix(".zip"))) return false
        return url.toString() == GithubProxy.wrap(RELEASE_BASE + name)
    }

    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            if (!allows(chain.request().url)) throw IOException("Unexpected sticker download host")
            chain.proceed(chain.request())
        }
        .build()
}
