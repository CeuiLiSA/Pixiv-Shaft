package ceui.lisa.download

import android.app.Application
import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.helper.Android10DownloadFactory22
import ceui.lisa.helper.SAFactory
import ceui.lisa.utils.Params
import ceui.lisa.utils.Settings
import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.api.model.MetaSinglePage
import ceui.pixiv.download.Downloads
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.aria2.Aria2Dispatcher
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadResolutionMetadataTest {
    private lateinit var context: Context
    private var oldSettings: Settings? = null
    private var oldContext: Context? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Utils.init(context as Application)
        oldSettings = Shaft.sSettings
        oldContext = Shaft.getContext()
        Shaft.sSettings = Settings().apply { defaultImageResolution = Params.IMAGE_RESOLUTION_LARGE }
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", context)
    }

    @After
    fun tearDown() {
        DownloadsRegistry.invalidateBackends()
        Shaft.sSettings = oldSettings
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", oldContext)
    }

    @Test
    fun `download record name and restored task use the selected JPEG extension`() {
        val item = task(singlePage())
        assertTrue(item.name, item.name.endsWith(".jpg"))
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(item), DownloadItem::class.java)
        assertEquals(item.name, restored.name)
        assertEquals(JPEG_URL, restored.url)
    }

    @Test
    fun `both storage factories retain selected URL and JPEG MIME after setting changes`() {
        val item = task(singlePage())
        Shaft.sSettings.defaultImageResolution = Params.IMAGE_RESOLUTION_ORIGINAL
        assertPlans(item, JPEG_URL, "jpg", "image/jpeg")
    }

    @Test
    fun `multi page download uses selected page format rather than cover format`() {
        val illust = singlePage().copy(
            page_count = 2,
            meta_single_page = null,
            meta_pages = listOf(
                MetaPage(ImageUrls(original = ORIGINAL_URL)),
                MetaPage(ImageUrls(original = "https://img.test/12_p1.png", large = "https://img.test/12_p1.jpg")),
            ),
        )
        assertPlans(task(illust, 1), "https://img.test/12_p1.jpg", "jpg", "image/jpeg")
    }

    @Test
    fun `missing selected resolution preserves actual PNG fallback format`() {
        val illust = singlePage().copy(image_urls = ImageUrls())
        assertPlans(task(illust), ORIGINAL_URL, "png", "image/png")
    }

    @Test
    fun `unset resolution still downloads original PNG`() {
        Shaft.sSettings.defaultImageResolution = ""
        assertPlans(task(singlePage()), ORIGINAL_URL, "png", "image/png")
    }

    @Test
    fun `ugoira URL keeps the intermediate zip name`() {
        val item = DownloadItem(singlePage().copy(type = "ugoira"), 0)
        val name = item.name
        item.url = "https://img.test/12_ugoira.zip"
        assertTrue(name.endsWith(".zip"))
        assertEquals(name, item.name)
    }

    @Test
    fun `aria2 receives a JPEG filename matching the selected download URL`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":"shaft","result":"gid"}"""))
            Shaft.sSettings.aria2RpcUrl = server.url("/jsonrpc").toString()
            val item = task(singlePage())
            assertEquals("gid", Aria2Dispatcher.dispatch(item))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val params = JsonParser.parseString(request.body.readUtf8()).asJsonObject.getAsJsonArray("params")
            assertEquals(JPEG_URL, params[0].asJsonArray[0].asString)
            val out = params[1].asJsonObject.get("out").asString
            assertTrue(out, out.endsWith(".jpg"))
            assertEquals(item.name, out.substringAfterLast('/'))
        } finally {
            server.shutdown()
        }
    }

    private fun task(illust: Illust, page: Int = 0) = DownloadItem(illust, page).apply {
        url = IllustDownload.getUrl(illust, page, IllustDownload.defaultImageResolution())
    }

    private fun assertPlans(item: DownloadItem, url: String, ext: String, mime: String) {
        val plans = listOf(
            ReflectionHelpers.getField<Downloads.Plan>(Android10DownloadFactory22(context, item), "plan"),
            ReflectionHelpers.getField<Downloads.Plan>(SAFactory(context, item), "mPlan"),
        )
        for (plan in plans) {
            assertEquals(url, plan.item.sourceUrl)
            assertEquals(ext, plan.item.ext)
            assertEquals(mime, plan.item.mime)
            assertTrue(plan.path.filename, plan.path.filename.endsWith(".$ext"))
            assertEquals(item.name, plan.path.filename)
        }
    }

    private fun singlePage() = Illust(
        id = 12L,
        title = "resolution",
        type = "illust",
        create_date = "2026-09-21T00:00:00+09:00",
        page_count = 1,
        image_urls = ImageUrls(large = JPEG_URL),
        meta_single_page = MetaSinglePage(original_image_url = ORIGINAL_URL),
    )

    private companion object {
        const val ORIGINAL_URL = "https://img.test/12_p0.png"
        const val JPEG_URL = "https://img.test/12_p0_master1200.jpg?source=png#preview"
    }
}
