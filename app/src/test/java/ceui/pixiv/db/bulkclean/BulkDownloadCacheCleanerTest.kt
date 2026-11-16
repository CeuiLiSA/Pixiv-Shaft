package ceui.pixiv.db.bulkclean

import android.app.Application
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BulkDownloadCacheCleanerTest {
    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var raw: SupportSQLiteDatabase

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AppDatabase.destroyInstance()
        context.deleteDatabase(AppDatabase.DATABASE_NAME)
        db = AppDatabase.getAppDatabase(context)
        raw = db.openHelper.writableDatabase
        assertTrue(raw.isWriteAheadLoggingEnabled)
        raw.query("PRAGMA wal_autocheckpoint=0").use { it.moveToFirst() }
        raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    @After fun tearDown() {
        db.close()
        AppDatabase.destroyInstance()
        context.deleteDatabase(AppDatabase.DATABASE_NAME)
    }

    @Test fun `small table includes committed records still in WAL`() {
        assertWalEstimate(rowCount = 2, payload = "画".repeat(60_000))
    }

    @Test fun `large table sample includes committed records still in WAL`() {
        assertWalEstimate(rowCount = 201, payload = "画".repeat(4_000))
    }

    private fun assertWalEstimate(rowCount: Int, payload: String) {
        val expectedBytes = payload.toByteArray(Charsets.UTF_8).size.toLong() * rowCount
        db.downloadDao().insertIgnoreAll((1..rowCount).map { i ->
            DownloadEntity().apply {
                fileName = "$i.png"
                illustGson = payload
                illustId = i.toLong()
            }
        })
        val mainFileBytes = context.getDatabasePath(AppDatabase.DATABASE_NAME).length()
        assertTrue("Fixture must keep the payload in WAL", mainFileBytes < expectedBytes)

        val stats = BulkDownloadCacheCleaner.estimateStorageStats(context, CancellationSignal())
        assertEquals(rowCount, stats.downloadRecordsCount)
        assertEquals(expectedBytes, stats.downloadRecordsBytes)
        assertEquals(expectedBytes, stats.totalBytes)
        assertEquals(expectedBytes, BulkDownloadCacheCleaner.computeStorageStats(context, CancellationSignal()).totalBytes)
    }

    @Test fun `skewed sample remains bounded by logical database size`() {
        db.downloadDao().insertIgnoreAll((1..201).map { i ->
            DownloadEntity().apply {
                fileName = "$i.png"
                illustGson = if (i <= 50 || i > 151) "x".repeat(10_000) else "x"
                illustId = i.toLong()
            }
        })
        val pageCount = raw.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
        val logicalBytes = pageCount * raw.pageSize
        val stats = BulkDownloadCacheCleaner.estimateStorageStats(context, CancellationSignal())
        assertEquals(logicalBytes, stats.downloadRecordsBytes)
    }

    @Test fun `empty tables have zero storage estimate`() {
        assertEquals(0L, BulkDownloadCacheCleaner.estimateReclaimableBytes(context, CancellationSignal()))
    }
}
