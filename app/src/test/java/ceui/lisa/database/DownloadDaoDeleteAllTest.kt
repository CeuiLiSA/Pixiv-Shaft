package ceui.lisa.database

import android.app.Application
import androidx.room.Room
import ceui.pixiv.db.queue.DownloadQueueEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #1192：整表删除改成每批 [DownloadDao.DELETE_BATCH] 行的短事务循环。
 * 行数跨多个批次时必须删干净，不能只删掉第一批。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadDaoDeleteAllTest {

    private lateinit var db: AppDatabase
    private val rows = DownloadDao.DELETE_BATCH * 2 + 50

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
    }

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst(); it.getInt(0)
        }

    @Test fun `deleteAllDownload 跨批次删干净`() {
        val dao = db.downloadDao()
        dao.insertIgnoreAll((1..rows).map { i ->
            DownloadEntity().apply {
                fileName = "f$i.png"; illustGson = "{}"; downloadTime = i.toLong(); illustId = i.toLong()
            }
        })
        assertEquals(rows, count("illust_download_table"))

        dao.deleteAllDownload()

        assertEquals(0, count("illust_download_table"))
    }

    @Test fun `deleteAllDownloading 跨批次删干净`() {
        val dao = db.downloadDao()
        (1..rows).forEach { i ->
            dao.insertDownloading(DownloadingEntity().apply { fileName = "f$i.png"; uuid = "u$i"; taskGson = "{}" })
        }
        assertEquals(rows, count("illust_downloading_table"))

        dao.deleteAllDownloading()

        assertEquals(0, count("illust_downloading_table"))
    }

    @Test fun `download_queue deleteAll 跨批次删干净`() = runBlocking {
        val dao = db.downloadQueueDao()
        dao.insertAll((1..rows).map { i -> DownloadQueueEntity(illustId = i.toLong(), seq = i.toLong()) })
        assertEquals(rows, count("download_queue"))

        dao.deleteAll()

        assertEquals(0, count("download_queue"))
    }
}
