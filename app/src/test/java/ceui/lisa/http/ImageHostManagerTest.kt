package ceui.lisa.http

import ceui.lisa.http.ImageHostManager.Mode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ImageHostManager is a singleton (`object`) — state leaks across tests if
 * not reset, which would cause order-dependent failures. Reset in both
 * @Before and @After to keep the foundation deterministic.
 */
class ImageHostManagerTest {

    @Before fun reset() = clear()
    @After fun cleanup() = clear()

    private fun clear() {
        ImageHostManager.setMode(Mode.PIXIV)
        ImageHostManager.setCustomHost("")
    }

    // --- PIXIV (default) -----------------------------------------------------

    @Test fun `PIXIV mode leaves i_pximg URL unchanged`() {
        val url = "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/123_p0.jpg"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    @Test fun `PIXIV mode leaves s_pximg URL unchanged`() {
        val url = "https://s.pximg.net/common/images/no_profile.png"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    @Test fun `default mode is PIXIV`() {
        assertEquals(Mode.PIXIV, ImageHostManager.getMode())
    }

    // --- PIXIV_CAT -----------------------------------------------------------

    @Test fun `PIXIV_CAT maps i_pximg to i_pixiv_cat preserving path`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        assertEquals(
            "https://i.pixiv.cat/img-original/img/2024/01/01/00/00/00/123_p0.jpg",
            ImageHostManager.rewrite("https://i.pximg.net/img-original/img/2024/01/01/00/00/00/123_p0.jpg")
        )
    }

    // issue #1152: s.pixiv.cat/.re/.nl 不存在(NXDOMAIN)，s.pximg.net 必须保持原样。
    @Test fun `PIXIV_CAT leaves s_pximg unchanged`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        val url = "https://s.pximg.net/common/images/no_profile.png"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    @Test fun `PIXIV_CAT preserves query string`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        assertEquals(
            "https://i.pixiv.cat/path/foo.jpg?bar=1&baz=2",
            ImageHostManager.rewrite("https://i.pximg.net/path/foo.jpg?bar=1&baz=2")
        )
    }

    @Test fun `PIXIV_CAT does not touch unknown hosts`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        val url = "https://example.com/foo.jpg"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    // --- PIXIV_RE (mainland China mirror of pixiv.cat) ----------------------

    @Test fun `PIXIV_RE maps i_pximg to i_pixiv_re preserving path`() {
        ImageHostManager.setMode(Mode.PIXIV_RE)
        assertEquals(
            "https://i.pixiv.re/img-original/img/2024/01/01/00/00/00/123_p0.jpg",
            ImageHostManager.rewrite("https://i.pximg.net/img-original/img/2024/01/01/00/00/00/123_p0.jpg")
        )
    }

    @Test fun `PIXIV_RE leaves s_pximg stamp unchanged`() {
        ImageHostManager.setMode(Mode.PIXIV_RE)
        val url = "https://s.pximg.net/common/images/stamp/generated-stamps/101_s.jpg?20180605"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    @Test fun `PIXIV_RE preserves query string and does not touch unknown hosts`() {
        ImageHostManager.setMode(Mode.PIXIV_RE)
        assertEquals(
            "https://i.pixiv.re/path/foo.jpg?bar=1",
            ImageHostManager.rewrite("https://i.pximg.net/path/foo.jpg?bar=1")
        )
        assertEquals("https://example.com/foo.jpg", ImageHostManager.rewrite("https://example.com/foo.jpg"))
    }

    @Test fun `requiresStandardClient is true for PIXIV_RE`() {
        ImageHostManager.setMode(Mode.PIXIV_RE)
        assertTrue(ImageHostManager.requiresStandardClient())
    }

    // --- PIXIV_NL (backup mirror) -------------------------------------------

    @Test fun `PIXIV_NL maps i_pximg to pixiv_nl and leaves s_pximg unchanged`() {
        ImageHostManager.setMode(Mode.PIXIV_NL)
        assertEquals(
            "https://i.pixiv.nl/img/x.jpg",
            ImageHostManager.rewrite("https://i.pximg.net/img/x.jpg")
        )
        val url = "https://s.pximg.net/common/images/no_profile.png"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    // --- CUSTOM --------------------------------------------------------------

    @Test fun `CUSTOM with blank host falls back to original`() {
        ImageHostManager.setMode(Mode.CUSTOM)
        val url = "https://i.pximg.net/img-original/img/x.jpg"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    @Test fun `CUSTOM replaces scheme and host with configured prefix`() {
        ImageHostManager.setMode(Mode.CUSTOM)
        ImageHostManager.setCustomHost("https://my.proxy")
        assertEquals(
            "https://my.proxy/img-original/img/x.jpg",
            ImageHostManager.rewrite("https://i.pximg.net/img-original/img/x.jpg")
        )
    }

    @Test fun `CUSTOM keeps a sub-path prefix in the configured host`() {
        ImageHostManager.setMode(Mode.CUSTOM)
        ImageHostManager.setCustomHost("https://my.proxy/pixiv")
        assertEquals(
            "https://my.proxy/pixiv/img-original/img/x.jpg",
            ImageHostManager.rewrite("https://i.pximg.net/img-original/img/x.jpg")
        )
    }

    @Test fun `CUSTOM applies to s_pximg too`() {
        ImageHostManager.setMode(Mode.CUSTOM)
        ImageHostManager.setCustomHost("https://my.proxy")
        assertEquals(
            "https://my.proxy/common/images/no_profile.png",
            ImageHostManager.rewrite("https://s.pximg.net/common/images/no_profile.png")
        )
    }

    @Test fun `CUSTOM allows http scheme in custom host`() {
        ImageHostManager.setMode(Mode.CUSTOM)
        ImageHostManager.setCustomHost("http://internal.lan:8080")
        assertEquals(
            "http://internal.lan:8080/img-original/img/x.jpg",
            ImageHostManager.rewrite("https://i.pximg.net/img-original/img/x.jpg")
        )
    }

    @Test fun `CUSTOM does not touch unknown hosts`() {
        ImageHostManager.setMode(Mode.CUSTOM)
        ImageHostManager.setCustomHost("https://my.proxy")
        val url = "https://example.com/foo.jpg"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    // --- setCustomHost normalization ----------------------------------------

    @Test fun `setCustomHost trims surrounding whitespace`() {
        ImageHostManager.setCustomHost("  https://my.proxy  ")
        assertEquals("https://my.proxy", ImageHostManager.getCustomHost())
    }

    @Test fun `setCustomHost strips trailing slash`() {
        ImageHostManager.setCustomHost("https://my.proxy/")
        assertEquals("https://my.proxy", ImageHostManager.getCustomHost())
    }

    @Test fun `setCustomHost strips multiple trailing slashes`() {
        ImageHostManager.setCustomHost("https://my.proxy///")
        assertEquals("https://my.proxy", ImageHostManager.getCustomHost())
    }

    @Test fun `setCustomHost preserves an inner path prefix`() {
        ImageHostManager.setCustomHost("https://my.proxy/pixiv/")
        assertEquals("https://my.proxy/pixiv", ImageHostManager.getCustomHost())
    }

    // --- rewrite() defensive boundaries -------------------------------------

    @Test fun `rewrite empty string returns empty`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        assertEquals("", ImageHostManager.rewrite(""))
    }

    @Test fun `rewrite without scheme returns input unchanged`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        val url = "i.pximg.net/foo.jpg"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    @Test fun `rewrite still maps a host-only URL with no path`() {
        // "https://i.pximg.net" has no '/' after the host. Unlikely in real
        // Pixiv data (image URLs always have a path), but we still recognize
        // the host and rewrite, since dropping the rewrite here would silently
        // leak the original host on whatever path the caller appends later.
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        assertEquals("https://i.pixiv.cat", ImageHostManager.rewrite("https://i.pximg.net"))
    }

    @Test fun `rewrite tolerates explicit port on pximg host`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        assertEquals(
            "https://i.pixiv.cat/foo.jpg",
            ImageHostManager.rewrite("https://i.pximg.net:443/foo.jpg")
        )
    }

    @Test fun `rewrite leaves arbitrary subdomains of pximg alone`() {
        // Only i.pximg.net and s.pximg.net are recognized image hosts.
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        val url = "https://app-api.pximg.net/foo"
        assertEquals(url, ImageHostManager.rewrite(url))
    }

    // --- requiresStandardClient ---------------------------------------------

    @Test fun `requiresStandardClient is false for PIXIV`() {
        ImageHostManager.setMode(Mode.PIXIV)
        assertFalse(ImageHostManager.requiresStandardClient())
    }

    @Test fun `requiresStandardClient is true for PIXIV_CAT`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        assertTrue(ImageHostManager.requiresStandardClient())
    }

    @Test fun `requiresStandardClient is true for CUSTOM`() {
        ImageHostManager.setMode(Mode.CUSTOM)
        assertTrue(ImageHostManager.requiresStandardClient())
    }

    // --- mode switching is reflected immediately ----------------------------

    @Test fun `mode change takes effect on next rewrite call`() {
        val url = "https://i.pximg.net/img/x.jpg"
        assertEquals(url, ImageHostManager.rewrite(url))   // PIXIV
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        assertEquals("https://i.pixiv.cat/img/x.jpg", ImageHostManager.rewrite(url))
    }

    // --- ordinal <-> mode (Settings int persistence) ------------------------

    @Test fun `getModeOrdinal matches enum ordinal`() {
        ImageHostManager.setMode(Mode.PIXIV)
        assertEquals(0, ImageHostManager.getModeOrdinal())
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        assertEquals(1, ImageHostManager.getModeOrdinal())
        ImageHostManager.setMode(Mode.PIXIV_RE)
        assertEquals(2, ImageHostManager.getModeOrdinal())
        ImageHostManager.setMode(Mode.PIXIV_NL)
        assertEquals(3, ImageHostManager.getModeOrdinal())
        ImageHostManager.setMode(Mode.CUSTOM)
        assertEquals(4, ImageHostManager.getModeOrdinal())
    }

    @Test fun `setModeOrdinal maps each ordinal to its mode`() {
        ImageHostManager.setModeOrdinal(0)
        assertEquals(Mode.PIXIV, ImageHostManager.getMode())
        ImageHostManager.setModeOrdinal(1)
        assertEquals(Mode.PIXIV_CAT, ImageHostManager.getMode())
        ImageHostManager.setModeOrdinal(2)
        assertEquals(Mode.PIXIV_RE, ImageHostManager.getMode())
        ImageHostManager.setModeOrdinal(3)
        assertEquals(Mode.PIXIV_NL, ImageHostManager.getMode())
        ImageHostManager.setModeOrdinal(4)
        assertEquals(Mode.CUSTOM, ImageHostManager.getMode())
    }

    @Test fun `setModeOrdinal clamps out-of-range to PIXIV`() {
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        ImageHostManager.setModeOrdinal(99)
        assertEquals(Mode.PIXIV, ImageHostManager.getMode())
        ImageHostManager.setMode(Mode.PIXIV_CAT)
        ImageHostManager.setModeOrdinal(-1)
        assertEquals(Mode.PIXIV, ImageHostManager.getMode())
    }
}
