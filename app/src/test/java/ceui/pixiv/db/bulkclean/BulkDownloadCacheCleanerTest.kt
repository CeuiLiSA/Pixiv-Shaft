package ceui.pixiv.db.bulkclean

import android.app.Application
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.sqlite.db.SupportSQLiteDatabase
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import kotlinx.coroutines.runBlocking
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

    @Test fun `record threshold still uses exact byte lengths`() {
        assertWalEstimate(rowCount = 200, payload = "画".repeat(4_000))
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

    @Test fun `estimation without a cancellation signal still reads stored records`() {
        db.downloadDao().insertIgnoreAll(listOf(DownloadEntity().apply {
            fileName = "1.png"
            illustGson = "record"
            illustId = 1L
        }))
        assertEquals(6L, BulkDownloadCacheCleaner.estimateStorageStats(context).totalBytes)
        assertEquals(6L, BulkDownloadCacheCleaner.estimateReclaimableBytes(context))
        assertEquals(6L, BulkDownloadCacheCleaner.estimateStorageStats(context, null).totalBytes)
        assertEquals(6L, BulkDownloadCacheCleaner.estimateReclaimableBytes(context, null))
    }

    @Test fun `queue threshold counts only completed payloads`() = assertQueueEstimate(100)

    @Test fun `large queue samples only completed payloads`() = assertQueueEstimate(101)

    @Test fun `skewed queue estimate stays inside database capacity`() = assertSkewedQueueBound(false)

    @Test fun `record and queue estimates share one bound and staging is added separately`() =
        assertSkewedQueueBound(true)

    private fun assertSkewedQueueBound(includeRecords: Boolean) = runBlocking {
        if (includeRecords) {
            db.downloadDao().insertIgnoreAll((1..201).map { i ->
                DownloadEntity().apply {
                    fileName = "$i.png"
                    illustGson = "x".repeat(10_000)
                    illustId = i.toLong()
                }
            })
        }
        db.downloadQueueDao().insertAll((1..1001).map { i ->
            DownloadQueueEntity(illustId = i.toLong(), seq = i.toLong(), status = QueueStatus.SUCCESS,
                illustGson = if (i <= 25 || i > 976) "x".repeat(10_000) else "x")
        })
        val pageCount = raw.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
        val databaseBytes = pageCount * raw.pageSize
        assertTrue("Fixture must overestimate the queue", databaseBytes < 10_010_000L)
        val staging = context.cacheDir.resolve("staging_dl").apply { mkdirs() }
        try {
            staging.resolve("payload.bin").writeBytes(ByteArray(1024))
            val stats = BulkDownloadCacheCleaner.estimateStorageStats(context)
            assertEquals(if (includeRecords) 201 else 0, stats.downloadRecordsCount)
            assertEquals(1001, stats.queueSuccessCount)
            assertTrue("Database components must share the capacity bound",
                stats.downloadRecordsBytes + stats.queuePayloadBytes <= databaseBytes)
            assertEquals(1024L, stats.stagingBytes)
            assertEquals(stats.downloadRecordsBytes + stats.queuePayloadBytes + 1024L, stats.totalBytes)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun assertQueueEstimate(rowCount: Int) = runBlocking {
        val payload = "队列".repeat(100)
        val expectedBytes = payload.toByteArray(Charsets.UTF_8).size.toLong() * rowCount
        val completed = (1..rowCount).map { i ->
            DownloadQueueEntity(illustId = i.toLong(), seq = i.toLong(),
                status = QueueStatus.SUCCESS, illustGson = payload)
        }
        val pending = DownloadQueueEntity(illustId = 999L, seq = 999L,
            status = QueueStatus.PENDING, illustGson = "x".repeat(100_000))
        db.downloadQueueDao().insertAll(completed + pending)

        val stats = BulkDownloadCacheCleaner.estimateStorageStats(context)
        assertEquals(rowCount, stats.queueSuccessCount)
        assertEquals(expectedBytes, stats.queuePayloadBytes)
        assertEquals(expectedBytes, stats.totalBytes)
        assertEquals(expectedBytes,
            BulkDownloadCacheCleaner.computeStorageStats(context, CancellationSignal()).totalBytes)
    }

    @Test(expected = OperationCanceledException::class)
    fun `cancelled estimate propagates cancellation`() {
        val signal = CancellationSignal().apply { cancel() }
        BulkDownloadCacheCleaner.estimateStorageStats(context, signal)
    }
}
