package ceui.pixiv.imageloader

import android.app.Application
import android.net.Uri
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import ceui.pixiv.snapshot.SnapshotRepository
import ceui.pixiv.testing.ReadablePageFiles
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 四规则的**优先级**是这套统一模型的核心承诺：本地 URL → 任务表 → 下载库 → 网络。
 *
 * 前两条必须赢过第 3 条，否则快照模式会被「当前下载的那一份」顶掉、已经下好的图会被再查一次库；
 * 而第 3 条必须在第 4 条之前，否则「本地已下载还重新走网络」这个 bug 就又回来了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PageImageSourceResolverTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private lateinit var db: AppDatabase

    private val illust = Illust(id = 4242L, title = "t", page_count = 2)

    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sGson = Gson()
        Shaft.sSettings = Settings()
    }

    @After
    fun tearDown() {
        AppDatabase.destroyInstance()
        db.close()
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `empty url is unavailable`() = runTest {
        assertEquals(PageImageSource.Unavailable, PageImageSourceResolver.resolve(app, illust, 0, null))
        assertEquals(PageImageSource.Unavailable, PageImageSourceResolver.resolve(app, illust, 0, ""))
    }

    @Test
    fun `content uri is a local url and carries no file`() = runTest {
        val url = "content://media/external/images/media/77"

        val local = PageImageSourceResolver.resolve(app, illust, 0, url) as? PageImageSource.Local

        assertNotNull("content:// 应当被规则 1 判成本地", local)
        assertEquals(PageImageSource.Origin.LocalUrl, local!!.origin)
        assertEquals(Uri.parse(url), local.uri)
        assertNull("content:// 只有可读流，不该在这里拷成文件", local.file)
    }

    @Test
    fun `file uri resolves to the real file`() = runTest {
        val file = tempImageFile("file-uri")

        // Uri.fromFile 在 Windows 上会把盘符吞进 authority（绝对路径不以 / 开头），
        // 手工拼成 file:///<forward-slashed path> 才是 Android 上的真实形态。
        val url = "file:///" + file.absolutePath.replace('\\', '/')
        val local = PageImageSourceResolver.resolve(app, illust, 0, url) as? PageImageSource.Local

        assertEquals(PageImageSource.Origin.LocalUrl, local?.origin)
        assertEquals(file, local?.file)
    }

    @Test
    fun `a bare absolute path is treated as a local file path`() = runTest {
        // 下载详情模式（DoneListV3Fragment）历史上会把记录里的 filePath 直接当 url 塞进来。
        val local = PageImageSourceResolver.resolve(app, illust, 0, "/definitely/not/here.jpg")
            as? PageImageSource.Local

        assertEquals(PageImageSource.Origin.LocalUrl, local?.origin)
        assertNull("文件不存在时不给 File，渲染层自己走 onError 回退", local?.file)
    }

    @Test
    fun `snapshot url wins over the download record`() = runTest {
        val rel = "pages/0.jpg"
        val archived = File(SnapshotRepository.root(app), "snap-1/$rel").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 9, 9))
        }
        tempFiles += archived

        // 同一页还躺着一条下载记录：快照模式下它**不许**顶掉存档。
        insertRow(filePath = ReadablePageFiles.readable().toString(), page = 0)

        val local = PageImageSourceResolver.resolve(app, illust, 0, "shaftsnap://snap-1/$rel")
            as? PageImageSource.Local

        assertEquals(PageImageSource.Origin.LocalUrl, local?.origin)
        assertEquals(archived, local?.file)
    }

    @Test
    fun `cached task wins over the download record`() = runTest {
        insertRow(filePath = ReadablePageFiles.readable().toString(), page = 0)
        val url = networkUrl("cached")
        val cached = tempImageFile("cached")
        putCachedTask(url, cached)

        val local = PageImageSourceResolver.resolve(app, illust, 0, url) as? PageImageSource.Local

        assertEquals(PageImageSource.Origin.Cached, local?.origin)
        assertEquals(cached, local?.file)
    }

    @Test
    fun `download record is used when nothing else matches`() = runTest {
        val uri = ReadablePageFiles.readable()
        insertRow(filePath = uri.toString(), page = 0)

        val local = PageImageSourceResolver.resolve(app, illust, 0, networkUrl("plain"))
            as? PageImageSource.Local

        assertEquals(PageImageSource.Origin.Downloaded, local?.origin)
        assertEquals(uri, local?.uri)
    }

    @Test
    fun `allowNetwork false stops before the network`() = runTest {
        val source = PageImageSourceResolver.resolve(
            app, illust, 0, networkUrl("offline"), allowNetwork = false,
        )

        assertEquals(PageImageSource.Unavailable, source)
    }

    @Test
    fun `network fallback hands out the shared task`() = runTest {
        val url = networkUrl("remote")
        try {
            val source = PageImageSourceResolver.resolve(app, illust, 0, url)

            assertTrue("没有本地来源时应当交任务", source is PageImageSource.Remote)
            assertEquals(url, (source as PageImageSource.Remote).task.request.url)
        } finally {
            ImageTaskRegistry.remove(url)
        }
    }

    @Test
    fun `resolveCheap answers only the two zero IO rules`() {
        assertNull(PageImageSourceResolver.resolveCheap(null))
        assertNull(PageImageSourceResolver.resolveCheap(networkUrl("cheap-miss")))
        assertEquals(
            PageImageSource.Origin.LocalUrl,
            PageImageSourceResolver.resolveCheap("content://media/external/images/media/1")?.origin,
        )
    }

    @Test
    fun `awaitFile returns the local file without copying`() = runTest {
        val file = tempImageFile("await-direct")
        val source = PageImageSource.Local(Uri.fromFile(file), file, PageImageSource.Origin.LocalUrl)

        assertEquals(file, source.awaitFile(app))
    }

    @Test
    fun `awaitFile copies a content uri into the page cache`() = runTest {
        val uri = ReadablePageFiles.readable(byteArrayOf(4, 5, 6))
        val source = PageImageSource.Local(uri, null, PageImageSource.Origin.Downloaded)

        val file = source.awaitFile(app)

        assertNotNull(file)
        assertEquals(3L, file!!.length())
    }

    @Test
    fun `awaitFile maps unavailable to null`() = runTest {
        assertNull(PageImageSource.Unavailable.awaitFile(app))
    }

    private fun networkUrl(tag: String) = "https://i.pximg.net/img-original/img/$tag.jpg"

    private fun insertRow(filePath: String, page: Int) {
        db.downloadDao().insertDownload(
            DownloadEntity().apply {
                this.fileName = "downloaded-$page.jpg"
                this.filePath = filePath
                this.page = page
                this.illustGson = Shaft.sGson.toJson(illust)
                this.downloadTime = System.currentTimeMillis()
            },
        )
    }

    /** 真任务、假抓取：跑完就是 Success，于是 [ImageLoaderV3.peekFile] 真的命中。 */
    @Suppress("UNCHECKED_CAST")
    private fun putCachedTask(url: String, file: File) {
        val task = ImageLoadTask(
            request = ImageRequest(url),
            scope = CoroutineScope(Dispatchers.Unconfined),
            fetcher = object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File = file
            },
            elapsedRealtime = { 0L },
        )
        task.start()
        val taskMap = ReflectionHelpers.getField<MutableMap<String, ImageLoadTask>>(
            ImageTaskRegistry, "taskMap",
        )
        taskMap[url] = task
    }

    private fun tempImageFile(name: String): File =
        File.createTempFile("pagesource-$name-", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            tempFiles += this
        }
}
