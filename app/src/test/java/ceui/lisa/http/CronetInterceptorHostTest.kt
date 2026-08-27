package ceui.lisa.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CronetInterceptorHostTest {

    @Test
    fun `direct connect is restricted to explicit Pixiv services`() {
        assertTrue(CronetInterceptor.shouldInterceptHost("app-api.pixiv.net"))
        assertTrue(CronetInterceptor.shouldInterceptHost("OAUTH.SECURE.PIXIV.NET"))
        assertTrue(CronetInterceptor.shouldInterceptHost("www.pixiv.net"))
        assertTrue(CronetInterceptor.shouldInterceptHost("accounts.pixiv.net"))
        assertTrue(CronetInterceptor.shouldInterceptHost("comic.pixiv.net"))
        assertTrue(CronetInterceptor.shouldInterceptHost("api.fanbox.cc"))
    }

    @Test
    fun `pixshaft proxies and lookalike domains stay on OkHttp`() {
        assertFalse(CronetInterceptor.shouldInterceptHost("pixshaft.com"))
        assertFalse(CronetInterceptor.shouldInterceptHost("proxy.example.com"))
        assertFalse(CronetInterceptor.shouldInterceptHost("app-api.pixiv.net.example.com"))
        assertFalse(CronetInterceptor.shouldInterceptHost(null))
    }

    @Test
    fun `direct connect keeps its established total deadline`() {
        assertEquals(30_000L, CronetInterceptor.requestTimeoutMillis())
    }
}
