package ceui.lisa.database

import android.app.Application
import androidx.room.Room
import ceui.lisa.utils.Params
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 已完成 tab 的按作品分组计数、分页、搜索与整作品页读写/删除。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadDaoDoneCardCountTest {

    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
    }

    private fun insert(
        fileName: String,
        time: Long,
        id: Long = 0L,
        gson: String = "{}",
        page: Int = 0,
        filePath: String = "content://media/$fileName",
    ) {
        db.downloadDao().insertIgnoreAll(listOf(DownloadEntity().apply {
            this.fileName = fileName
            this.filePath = filePath
            illustGson = gson
            downloadTime = time
            illustId = id
            this.page = page
        }))
    }

    private fun count(keyword: String? = null) =
        db.downloadDao().countDoneCards(Params.NOVEL_KEY, keyword)

    private fun cards(keyword: String? = null, limit: Int = 100, offset: Int = 0) =
        db.downloadDao().getDoneCards(Params.NOVEL_KEY, keyword, limit, offset)

    @Test fun `空表是 0`() {
        assertEquals(0, count())
        assertEquals(emptyList<DoneDownloadCard>(), cards())
    }

    @Test fun `多页作品只算一张卡且代表行为最新下载页`() {
        insert("100_p0.png", 1L, id = 100L, page = 0)
        insert("100_p1.png", 5L, id = 100L, page = 1)
        insert("200_p0.png", 3L, id = 200L, page = 0)

        assertEquals(2, count())
        val result = cards()
        assertEquals(listOf("illust:100", "illust:200"), result.map { it.cardKey })
        assertEquals("100_p1.png", result[0].fileName)
        assertEquals(2, result[0].pageCount)
        assertEquals(5L, result[0].downloadTime)
    }

    @Test fun `小说和解析失败的行各算一张卡`() {
        insert("${Params.NOVEL_KEY}1", 1L, id = 1L)
        insert("${Params.NOVEL_KEY}2", 2L, id = 1L)
        insert("broken_a.png", 3L, id = -1L)
        insert("broken_b.png", 4L, id = -1L)

        assertEquals(4, count())
    }

    @Test fun `按作品分页不被单作品页数截断`() {
        insert("old_p0.png", 1L, id = 100L, page = 0)
        insert("new_p0.png", 2L, id = 200L, page = 0)
        insert("new_p1.png", 3L, id = 200L, page = 1)
        insert("new_p2.png", 4L, id = 200L, page = 2)

        assertEquals(2, count())
        val page1 = cards(limit = 1, offset = 0)
        val page2 = cards(limit = 1, offset = 1)
        assertEquals(listOf("illust:200"), page1.map { it.cardKey })
        assertEquals(3, page1.single().pageCount)
        assertEquals(listOf("illust:100"), page2.map { it.cardKey })
    }

    @Test fun `搜索命中非最新页仍返回整个作品及其完整页数和最新代表行`() {
        insert("100_p0_needle.png", 1L, id = 100L, gson = """{"id":100,"title":"alpha"}""", page = 0)
        insert("100_p1.png", 10L, id = 100L, gson = """{"id":100,"title":"alpha"}""", page = 1)
        insert("200_p0.png", 20L, id = 200L, gson = """{"id":200,"title":"beta"}""", page = 0)

        assertEquals(1, count(keyword = "needle"))
        val hits = cards(keyword = "needle")
        assertEquals(1, hits.size)
        assertEquals("illust:100", hits[0].cardKey)
        assertEquals("100_p1.png", hits[0].fileName)
        assertEquals(2, hits[0].pageCount)
    }

    @Test fun `未回填行按 JSON 兜底归类，已确认解析失败行不误用嵌套 id`() {
        // illustId=0 未回填行：应按 JSON 顶层 id 归并到 100
        insert("legacy_100_p0.png", 1L, id = 0L, gson = """{"id":100,"title":"ok"}""", page = 0)
        insert("indexed_100_p1.png", 2L, id = 100L, gson = """{"id":100,"title":"ok"}""", page = 1)
        // illustId=-1 已确认无顶层 id，但嵌套 user.id=100：必须保持独立 anon 卡，不能并入 illust:100
        insert("broken_nested.png", 3L, id = -1L, gson = """{"user":{"id":100}}""", page = -2)
        // 同 id 小说必须与插画隔离
        insert("${Params.NOVEL_KEY}100", 4L, id = 100L, gson = """{"id":100}""")

        assertEquals(3, count())
        val allCards = cards().associateBy { it.cardKey }
        assertEquals(2, allCards["illust:100"]?.pageCount)
        assertEquals(1, allCards["anon:broken_nested.png"]?.pageCount)
        assertEquals(1, allCards["novel:${Params.NOVEL_KEY}100"]?.pageCount)

        val pages = db.downloadDao().getDoneIllustPages(100L, Params.NOVEL_KEY)
        assertEquals(setOf("legacy_100_p0.png", "indexed_100_p1.png"), pages.map { it.fileName }.toSet())

        db.downloadDao().deleteDoneIllust(100L, Params.NOVEL_KEY)
        assertEquals(2, count())
        assertEquals(
            setOf("anon:broken_nested.png", "novel:${Params.NOVEL_KEY}100"),
            cards().map { it.cardKey }.toSet(),
        )
    }
}
