package ceui.lisa.core

import android.app.Application
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadingEntity
import ceui.lisa.utils.Settings
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.google.gson.JsonDeserializer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ManagerRestoreTest {
    private lateinit var db: AppDatabase
    private val gson = Gson()

    @Before fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        AppDatabase.destroyInstance()
        db.close()
    }

    private fun record(index: Int) = DownloadingEntity().apply {
        fileName = "1_p$index.jpg"
        uuid = "task-$index"
        taskGson = """{"name":"$fileName","uuid":"$uuid","url":"https://example.com/$index.jpg",
            "index":$index,"silent":true,"illust":{"id":1,"page_count":257,
            "caption":"${"description".repeat(8000)}","title":"Artwork"}}""".trimIndent()
    }

    @Test fun `restores all 257 records in enqueue order and shares multi page artwork`() {
        val dao = db.downloadDao()
        db.runInTransaction { repeat(257) { dao.insertDownloading(record(it)) } }
        val restored = Manager.readRestoredDownloads(dao, gson)
        assertEquals((0..256).map { "task-$it" }, restored.map { it.uuid })
        assertEquals((0..256).toList(), restored.map { it.index })
        assertTrue(restored.all { it.isSilent })
        restored.forEach { assertSame(restored.first().illust, it.illust) }
        assertEquals(88000, restored.first().illust.caption!!.length)
        dao.getDownloadingBatch(0, Long.MAX_VALUE, 1000).use { assertEquals(257, it.count) }
    }

    @Test fun `keyset query neither skips after deletion nor includes new tasks beyond the boundary`() {
        val dao = db.downloadDao()
        repeat(42) { dao.insertDownloading(record(it)) }
        val through = dao.getDownloadingHighWaterMark()
        val after = dao.getDownloadingBatch(0, through, 20).use {
            assertEquals(20, it.count)
            it.moveToLast()
            it.getLong(0)
        }
        dao.deleteDownloading(record(0))
        dao.insertDownloading(record(999))
        dao.getDownloadingBatch(after, through, 100).use {
            assertEquals(22, it.count)
            it.moveToFirst()
            assertEquals("task-20", gson.fromJson(it.getString(1), DownloadItem::class.java).uuid)
        }
    }

    @Test fun `malformed record is preserved and does not prevent later records from restoring`() {
        val dao = db.downloadDao()
        dao.insertDownloading(record(0).apply { taskGson = "{broken" })
        dao.insertDownloading(record(1))
        assertEquals(listOf("task-1"), Manager.readRestoredDownloads(dao, gson).map { it.uuid })
        dao.getDownloadingBatch(0, Long.MAX_VALUE, 20).use { assertEquals(2, it.count) }
    }

    @Test fun `different metadata for the same artwork must not be merged`() {
        val dao = db.downloadDao()
        dao.insertDownloading(record(0))
        dao.insertDownloading(record(1).apply { taskGson = taskGson.replace("Artwork", "Updated artwork") })
        val restored = Manager.readRestoredDownloads(dao, gson)
        assertNotSame(restored[0].illust, restored[1].illust)
        assertEquals("Updated artwork", restored[1].illust.title)
    }

    @Test fun `task added then deleted during recovery is not resurrected by the old snapshot`() {
        val parsed = CountDownLatch(1)
        val resume = CountDownLatch(1)
        db.downloadDao().insertDownloading(record(0))
        Shaft.sGson = gson.newBuilder().registerTypeAdapter(DownloadItem::class.java,
            JsonDeserializer<DownloadItem> { json, _, _ ->
                gson.fromJson(json, DownloadItem::class.java).also {
                    parsed.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                }
            }).create()
        Shaft.sSettings = Settings().apply { downloadLimitType = 2 }
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        val manager = ReflectionHelpers.callConstructor(Manager::class.java)
        manager.restore()
        assertTrue(parsed.await(10, TimeUnit.SECONDS))
        try {
            val live = gson.fromJson(record(0).taskGson, DownloadItem::class.java).apply { uuid = "live-task" }
            manager.addTask(live)
            // Wait for the existing asynchronous persistence before issuing the explicit delete.
            awaitCondition {
                db.openHelper.readableDatabase.query("SELECT uuid FROM illust_downloading_table").use {
                    it.moveToFirst() && it.getString(0) == live.uuid
                }
            }
            manager.clearOne(live.uuid)
        } finally {
            resume.countDown()
        }
        awaitCondition {
            synchronized(manager) {
                ReflectionHelpers.getField<Any?>(manager, "restoreLiveUrls") == null
            }
        }
        assertTrue(manager.contentSnapshot().isEmpty())
        assertEquals(0L, db.downloadDao().getDownloadingHighWaterMark())
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Asynchronous queue operation did not finish" }
            Thread.sleep(10)
        }
    }
}
