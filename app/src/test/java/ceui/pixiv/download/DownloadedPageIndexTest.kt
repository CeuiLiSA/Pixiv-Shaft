package ceui.pixiv.download

import android.app.Application
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.lisa.download.FileCreator
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import ceui.pixiv.testing.ReadablePageFiles
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 「这一页本地有没有」这份唯一规则的两段式，**两段都必须在**。
 *
 * 第 2 段（fileName 主键兜底）专门捞 v41 之前 `page = -1` 的存量行（DownloadPageBackfill 没跑完 /
 * 文件名解析不出页码，issue #953）。它曾经被抄漏过一次 —— 于是同一页在详情页能靠本地文件秒显、
 * AI 却落到网络重下。这里用真 Room 造出那种存量行，把「漏抄」这个回归钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadedPageIndexTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private lateinit var db: AppDatabase

    private val illust = Illust(id = 4242L, title = "t", page_count = 2)

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
    }

    @Test
    fun `page hits the compound index without consulting file names`() = runTest {
        val uri = ReadablePageFiles.readable()
        insertRow(fileName = "跟当前模板完全对不上的名字.jpg", filePath = uri.toString(), page = 0)

        assertEquals(uri, DownloadedPageIndex.page(app, illust, 0))
    }

    @Test
    fun `page falls back to the file name key for a legacy row with page = -1`() = runTest {
        val uri = ReadablePageFiles.readable()
        // 存量行：复合索引那一列还是 -1，只有 fileName 主键那条路兜得住。
        insertRow(fileName = FileCreator.customFileName(illust, 0), filePath = uri.toString(), page = -1)

        assertEquals(uri, DownloadedPageIndex.page(app, illust, 0))
    }

    @Test
    fun `page ignores a record whose file is gone`() = runTest {
        insertRow(
            fileName = "orphan.jpg",
            filePath = ReadablePageFiles.missing("orphan").toString(),
            page = 0,
        )

        assertNull(DownloadedPageIndex.page(app, illust, 0))
    }

    @Test
    fun `pages merges both stages and keeps only readable pages`() = runTest {
        val first = ReadablePageFiles.readable()
        insertRow(fileName = "旧模板 0.jpg", filePath = first.toString(), page = 0)

        val second = ReadablePageFiles.readable()
        insertRow(fileName = FileCreator.customFileName(illust, 1), filePath = second.toString(), page = -1)

        // 越界页不该出现；同页的孤儿行也不该遮住能读的那条。
        insertRow(fileName = "越界.jpg", filePath = ReadablePageFiles.readable().toString(), page = 9)
        insertRow(fileName = "孤儿.jpg", filePath = ReadablePageFiles.missing("gone").toString(), page = 1)

        val found = DownloadedPageIndex.pages(app, illust, pageCount = 2)

        assertEquals(setOf(0, 1), found.keys)
        assertEquals(first, found[0])
        assertEquals(second, found[1])
    }

    @Test
    fun `pages skips the pages the caller already confirmed`() = runTest {
        val known = ReadablePageFiles.readable()
        val fresh = ReadablePageFiles.readable()
        insertRow(fileName = "旧模板 0.jpg", filePath = known.toString(), page = 0)
        insertRow(fileName = FileCreator.customFileName(illust, 1), filePath = fresh.toString(), page = 1)

        // page 0 调用方已经确认可读 → 不该再出现在结果里，那一趟 openFileDescriptor 就省掉了。
        val found = DownloadedPageIndex.pages(app, illust, pageCount = 2, skip = setOf(0))

        assertEquals(setOf(1), found.keys)
        assertEquals(fresh, found[1])
    }

    private fun insertRow(fileName: String, filePath: String, page: Int) {
        db.downloadDao().insertDownload(
            DownloadEntity().apply {
                this.fileName = fileName
                this.filePath = filePath
                this.page = page
                this.illustGson = Shaft.sGson.toJson(illust)
                this.downloadTime = System.currentTimeMillis()
            },
        )
    }
}
