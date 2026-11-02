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

/** 已完成 tab 的数字：分组口径必须和列表一致，窗口只算最新的 limit 行。 */
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

    private fun insert(fileName: String, time: Long, id: Long = 0L) {
        db.downloadDao().insertIgnoreAll(listOf(DownloadEntity().apply {
            this.fileName = fileName
            illustGson = "{}"
            downloadTime = time
            illustId = id
        }))
    }

    private fun count(limit: Int = 600) = db.downloadDao().countDoneCardsInWindow(Params.NOVEL_KEY, limit)

    @Test fun `空表是 0`() {
        assertEquals(0, count())
    }

    @Test fun `多页作品只算一张卡`() {
        insert("100_p0.png", 1L, id = 100L)
        insert("100_p1.png", 2L, id = 100L)
        insert("200_p0.png", 3L, id = 200L)

        assertEquals(2, count())
    }

    @Test fun `小说和解析失败的行各算一张卡`() {
        insert("${Params.NOVEL_KEY}1", 1L, id = 1L)
        insert("${Params.NOVEL_KEY}2", 2L, id = 1L)
        insert("broken_a.png", 3L, id = -1L)
        insert("broken_b.png", 4L, id = -1L)

        assertEquals(4, count())
    }

    @Test fun `窗口外的行不计入`() {
        insert("old_p0.png", 1L, id = 100L)
        insert("new_p0.png", 2L, id = 200L)
        insert("new_p1.png", 3L, id = 200L)

        assertEquals(1, count(limit = 2))
    }
}
