package ceui.pixiv.sticker

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Content-addressed release assets; the legacy catalog URL is never downloaded. */
internal object StickerDownloadSource {
    const val RELEASE_BASE = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/sticker-assets/"
    private val digest = Regex("[a-f0-9]{64}")

    fun url(pkg: StickerPackage): String {
        require(digest.matches(pkg.sha256)) { "Missing sticker ZIP checksum" }
        return "$RELEASE_BASE${pkg.sha256}.zip"
    }

    fun allows(url: HttpUrl): Boolean {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null) return false
        return when (url.host) {
            "github.com" -> url.query == null &&
                url.encodedPath.substringAfterLast('/').let { name ->
                    url.toString() == RELEASE_BASE + name && name.endsWith(".zip") &&
                        digest.matches(name.removeSuffix(".zip"))
                }
            // GitHub redirects public release assets to its short-lived signed CDN URL.
            "release-assets.githubusercontent.com" -> true
            else -> false
        }
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
