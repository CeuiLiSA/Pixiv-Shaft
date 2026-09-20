package ceui.pixiv.sticker

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class StickerDownloadSourceTest {
    private val checksum = "a".repeat(64)
    private val pkg = StickerPackage(64, 64,
        "https://${StickerCatalog.COS_HOST}/public/stickers/emoji/emoji-pkg-64.zip",
        "emoji/64", 123, checksum)

    @Test fun `saved COS catalogs resolve to content addressed GitHub assets`() {
        assertEquals("${StickerDownloadSource.RELEASE_BASE}$checksum.zip", StickerDownloadSource.url(pkg))
        assertEquals(StickerDownloadSource.url(pkg), StickerDownloadSource.url(pkg.copy(url = "unused")))
        assertThrows(IllegalArgumentException::class.java) {
            StickerDownloadSource.url(pkg.copy(sha256 = "../archive"))
        }
    }

    @Test fun `redirect policy permits release CDN but forbids COS and unrelated URLs`() {
        val release = StickerDownloadSource.url(pkg)
        for (url in listOf(release, "https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?sig=xyz")) {
            assertTrue(url, StickerDownloadSource.allows(url.toHttpUrl()))
        }
        for (url in listOf(pkg.url, release.replace("https:", "http:"),
            release.replace("github.com/", "github.com:8443/"),
            release.replace("github.com/", "github.com.evil.test/"),
            release.replace("CeuiLiSA/", "someone/"),
            release.replace("sticker-assets/", "v4.9.4/"),
            release.replace(checksum, "unknown"), "$release?x=1", "$release#fragment",
            release.replace("github.com/", "user:password@github.com/"),
            "https://api.pixshaft.com/archive.zip", "https://objects.githubusercontent.com/archive.zip")) {
            assertFalse(url, StickerDownloadSource.allows(url.toHttpUrl()))
        }
    }

    @Test fun `only the sticker client follows HTTPS redirects`() {
        val base = OkHttpClient.Builder().followRedirects(false).build()
        val client = StickerDownloadSource.client(base)
        assertTrue(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(1, client.networkInterceptors.size)
        assertFalse(base.followRedirects)
        assertTrue(base.networkInterceptors.isEmpty())
    }
}
