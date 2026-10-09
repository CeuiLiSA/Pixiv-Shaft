package ceui.pixiv.webdav

import android.app.Application
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** [WebDavClient] 的协议层测试：MockWebServer 假扮 WebDAV 服务器。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebDavClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(folder: String = "apps/Shaft") = WebDavClient(
        WebDavConfig(
            baseUrl = WebDavConfig.normalizeBaseUrl(server.url("/dav").toString()),
            username = "user",
            password = "p@ss word",
            folder = folder,
        )
    )

    @Test
    fun `列目录只取文件并解码 href，兼容不同命名空间前缀`() {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/dav/apps/Shaft/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/dav/apps/Shaft/Shaft-Backup_20261009T073012Z_Pixel-8.json.gz</d:href>
                    <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength></d:prop></d:propstat>
                  </d:response>
                  <D:response xmlns:D="DAV:">
                    <D:href>http://example.com/dav/apps/Shaft/a%20b.txt</D:href>
                    <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
                  </D:response>
                </d:multistatus>
                """.trimIndent()
            )
        )

        val files = client().listFiles()

        assertEquals(
            listOf(
                WebDavEntry("Shaft-Backup_20261009T073012Z_Pixel-8.json.gz", 1234),
                WebDavEntry("a b.txt", 0),
            ),
            files,
        )
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav/apps/Shaft/", request.path)
        assertEquals("1", request.getHeader("Depth"))
        assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `目录不存在时列表为空`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(emptyList<WebDavEntry>(), client().listFiles())
    }

    @Test
    fun `逐级建目录，已存在的 405 视为成功`() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(201))

        client().ensureFolder()

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("MKCOL", first.method)
        assertEquals("/dav/apps/", first.path)
        assertEquals("/dav/apps/Shaft/", second.path)
    }

    @Test
    fun `上传写到备份目录下`() {
        server.enqueue(MockResponse().setResponseCode(201))
        val file = File.createTempFile("webdav", ".gz").apply { writeText("x") }
        try {
            client().upload("Shaft-Backup_x.json.gz", file, "application/gzip")
        } finally {
            file.delete()
        }

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/dav/apps/Shaft/Shaft-Backup_x.json.gz", request.path)
        assertEquals("x", request.body.readUtf8())
    }

    @Test
    fun `401 归类为认证失败`() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client().checkAccess()
            fail("expected WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.AUTH, e.kind)
            assertEquals(401, e.code)
        }
    }

    @Test
    fun `目录名里的点段被丢弃，不能跳出根地址`() {
        assertEquals("apps/Shaft", WebDavConfig.normalizeFolder("/apps//./../Shaft/"))
        assertEquals("https://dav.example.com/dav/", WebDavConfig.normalizeBaseUrl(" https://dav.example.com/dav "))
    }
}
