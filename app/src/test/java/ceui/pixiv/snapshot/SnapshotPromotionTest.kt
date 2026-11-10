package ceui.pixiv.snapshot

import android.content.Context
import android.content.ContextWrapper
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SnapshotPromotionTest.TestApplication::class)
class SnapshotPromotionTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @get:Rule val temp = TemporaryFolder()
    private val snapshots = mutableListOf<String>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Shaft.sGson = Gson()
        context = testContext()
    }

    @After
    fun tearDown() {
        snapshots.forEach {
            SnapshotRuntimeCache.remove(it)
            SnapshotIndex.onSnapshotDeleted(it)
        }
    }

    @Test
    fun `quota preserves a snapshot promoted after selection and still enforces quota`() = runBlocking {
        val selected = CountDownLatch(1)
        val allowDelete = CountDownLatch(1)
        val quotaReads = AtomicInteger()
        val quotaContext = testContext {
            // 第二次解析根目录发生在清理已选中目标、准备删除时。
            if (quotaReads.incrementAndGet() == 2) {
                selected.countDown()
                check(allowDelete.await(10, TimeUnit.SECONDS))
            }
        }
        val promoted = autoSnapshot("promoted", illustId = 101, size = 200, createdAt = 3)
        val oldest = autoSnapshot("oldest", illustId = 102, size = 40, createdAt = 1)
        val recent = autoSnapshot("recent", illustId = 103, size = 70, createdAt = 2)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "snapshot-quota-test") }
        try {
            val cleanup = executor.submit { runBlocking { AutoSnapshotRepository.enforceAutoQuota(quotaContext, maxBytes = 100) } }
            assertTrue(selected.await(10, TimeUnit.SECONDS))
            SnapshotPromoter.promote(context, promoted.name)
            allowDelete.countDown()
            cleanup.get(10, TimeUnit.SECONDS)

            assertFormalSnapshot(promoted, illustId = 101, createdAt = 3)
            assertFalse(oldest.exists())
            assertTrue(recent.isDirectory)
            assertEquals(listOf(recent.name), AutoSnapshotRepository.listAuto(context).map { it.manifest.snapshotId })
        } finally {
            allowDelete.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `deletion waits for promotion and preserves its committed files`() = runBlocking {
        val promoting = CountDownLatch(1)
        val allowPromotion = CountDownLatch(1)
        val promotionReads = AtomicInteger()
        val promotionContext = testContext {
            // 转正已开始，停在读取自动清单之前；此时还没有正式清单可供删除方检查。
            if (promotionReads.incrementAndGet() == 2) {
                promoting.countDown()
                check(allowPromotion.await(10, TimeUnit.SECONDS))
            }
        }
        val dir = autoSnapshot("promoting", illustId = 104)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val promotion = executor.submit<SnapshotManifest> { runBlocking { SnapshotPromoter.promote(promotionContext, dir.name) } }
            assertTrue(promoting.await(10, TimeUnit.SECONDS))
            // UNDISPATCHED 启动后直接运行到 Mutex 等待，避免用时间窗口猜测删除是否开始。
            val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                AutoSnapshotRepository.deleteAuto(context, dir.name)
            }
            assertFalse(deletion.isCompleted)
            allowPromotion.countDown()
            assertEquals(dir.name, promotion.get(10, TimeUnit.SECONDS).snapshotId)
            assertFalse(deletion.await())
            assertFormalSnapshot(dir, illustId = 104)
        } finally {
            allowPromotion.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `stale auto deletion preserves the formal snapshot cache and index`() = runBlocking {
        val dir = autoSnapshot("stale-auto", illustId = 105)
        SnapshotPromoter.promote(context, dir.name)

        assertFalse(AutoSnapshotRepository.deleteAuto(context, dir.name))

        assertFormalSnapshot(dir, illustId = 105)
    }

    @Test
    fun `ordinary auto deletion removes files cache and index`() = runBlocking {
        val dir = autoSnapshot("delete-auto", illustId = 106)

        assertTrue(AutoSnapshotRepository.deleteAuto(context, dir.name))

        assertFalse(dir.exists())
        assertNull(SnapshotRuntimeCache.get(dir.name))
        assertNull(SnapshotIndex.entryFor(106))
    }

    @Test
    fun `invalid auto snapshot is not promoted and stays deletable`() = runBlocking {
        val dir = autoSnapshot("invalid-auto", illustId = 107)
        File(dir, "cover.jpg").delete()

        val failure = runCatching { SnapshotPromoter.promote(context, dir.name) }.exceptionOrNull()

        assertTrue(failure is SnapshotException)
        assertFalse(File(dir, SNAPSHOT_MANIFEST).exists())
        assertTrue(File(dir, AUTO_SNAPSHOT_MANIFEST).isFile)
        assertEquals(SnapshotIndex.Entry(dir.name, true, 1), SnapshotIndex.entryFor(107))
        assertTrue(AutoSnapshotRepository.deleteAuto(context, dir.name))
    }

    @Test
    fun `failed formal manifest commit preserves auto manifest and cleans temporary file`() = runBlocking {
        val dir = autoSnapshot("failed-formal", illustId = 112)
        // 用同名目录阻止 rename，模拟清单提交失败。
        File(dir, SNAPSHOT_MANIFEST).mkdir()
        val failure = runCatching { SnapshotPromoter.promote(context, dir.name) }.exceptionOrNull()
        assertTrue(failure is SnapshotException)
        assertFalse(File(dir, SNAPSHOT_MANIFEST).isFile)
        assertTrue(File(dir, AUTO_SNAPSHOT_MANIFEST).isFile)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        assertEquals(SnapshotIndex.Entry(dir.name, true, 1), SnapshotIndex.entryFor(112))
        assertTrue(AutoSnapshotRepository.deleteAuto(context, dir.name))
    }

    @Test
    fun `cancelled deletion waiting for mutation lock leaves snapshot intact`() = runBlocking {
        val dir = autoSnapshot("cancel-delete", illustId = 108)
        AutoSnapshotRepository.mutationLock.withLock {
            val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                AutoSnapshotRepository.deleteAuto(context, dir.name)
            }
            assertFalse(deletion.isCompleted)
            deletion.cancelAndJoin()
        }
        assertTrue(dir.isDirectory)
        assertTrue(AutoSnapshotRepository.deleteAuto(context, dir.name))
    }

    @Test
    fun `failed publication rolls back before waiting promotion can read manifest`() = runBlocking(Dispatchers.IO) {
        val (dir, manifest) = unpublishedSnapshot("failed-publish", illustId = 109)
        var promotion: Deferred<Result<SnapshotManifest>>? = null
        val failure = runCatching {
            AutoSnapshotRepository.publishAuto(context, manifest, reusedCachedOriginal = true) {
                // 已写自动清单但尚未完成最终复核，转正必须在此处等待。
                promotion = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { SnapshotPromoter.promote(context, dir.name) }
                }
                assertFalse(promotion!!.isCompleted)
                0L
            }
        }.exceptionOrNull()
        assertTrue(failure is AutoSnapshotQuotaExceededException)
        assertTrue((failure as AutoSnapshotQuotaExceededException).reusedCachedOriginal)
        assertTrue(promotion!!.await().exceptionOrNull() is SnapshotException)
        assertFalse(dir.exists())
        assertNull(SnapshotIndex.entryFor(109))
    }

    @Test
    fun `successful publication finishes auto indexing before promotion`() = runBlocking(Dispatchers.IO) {
        val (dir, manifest) = unpublishedSnapshot("publish-promote", illustId = 110)
        var promotion: Deferred<SnapshotManifest>? = null
        AutoSnapshotRepository.publishAuto(context, manifest, reusedCachedOriginal = false) {
            promotion = async(start = CoroutineStart.UNDISPATCHED) {
                SnapshotPromoter.promote(context, dir.name)
            }
            assertFalse(promotion!!.isCompleted)
            Long.MAX_VALUE
        }
        promotion!!.await()
        assertFormalSnapshot(dir, illustId = 110)
    }

    @Test
    fun `cancelled unpublished snapshot is removed while waiting for mutation lock`() = runBlocking {
        val (dir, manifest) = unpublishedSnapshot("cancel-publish", illustId = 111)
        AutoSnapshotRepository.mutationLock.withLock {
            val publication = async(start = CoroutineStart.UNDISPATCHED) {
                AutoSnapshotRepository.publishAuto(context, manifest, reusedCachedOriginal = false)
            }
            assertFalse(publication.isCompleted)
            publication.cancelAndJoin()
            assertFalse(dir.exists())
        }
        assertNull(SnapshotIndex.entryFor(111))
    }

    private fun unpublishedSnapshot(id: String, illustId: Long): Pair<File, AutoSnapshotManifest> {
        val dir = autoSnapshot(id, illustId)
        val manifest = AutoSnapshotRepository.readAutoManifest(context, id)!!
        File(dir, AUTO_SNAPSHOT_MANIFEST).delete()
        SnapshotIndex.onSnapshotDeleted(id)
        SnapshotRuntimeCache.remove(id)
        return dir to manifest
    }

    private fun testContext(beforeRead: () -> Unit = {}): Context =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File {
                beforeRead()
                return temp.root
            }
        }

    private fun autoSnapshot(id: String, illustId: Long, size: Long = 40, createdAt: Long = 1): File {
        snapshots += id
        val dir = SnapshotRepository.createSnapshotDir(context, id)
        val manifest = AutoSnapshotManifest(
            snapshotId = id, illustId = illustId, createdAt = createdAt,
            coverPath = "cover.jpg", pageCount = 1, fileCount = 4, totalSize = size,
        )
        File(dir, AUTO_SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(manifest))
        File(dir, SNAPSHOT_ILLUST_JSON).writeText(Shaft.sGson.toJson(Illust(id = illustId, type = "illust", page_count = 1)))
        File(dir, SNAPSHOT_ASSETS_JSON).writeText(Shaft.sGson.toJson(SnapshotAssets()))
        File(dir, "cover.jpg").writeText("complete image")
        SnapshotIndex.onSnapshotWritten(illustId, id, isAuto = true, createdAt)
        SnapshotRuntimeCache.put(id, AutoSnapshotRepository.loadAutoViewerData(context, id))
        return dir
    }

    private fun assertFormalSnapshot(dir: File, illustId: Long, createdAt: Long = 1) {
        assertTrue("正式快照清单必须保留", File(dir, SNAPSHOT_MANIFEST).isFile)
        val manifest = SnapshotRepository.readManifest(context, dir.name)!!
        SnapshotValidator.validate(dir, manifest)
        assertEquals("complete image", File(dir, "cover.jpg").readText())
        assertFalse(File(dir, AUTO_SNAPSHOT_MANIFEST).exists())
        assertEquals(SnapshotIndex.Entry(dir.name, false, createdAt), SnapshotIndex.entryFor(illustId))
        assertEquals(manifest, SnapshotRuntimeCache.get(dir.name)?.manifest)
    }
}
