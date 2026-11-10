package ceui.lisa.core

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.DownloadFileFactory
import ceui.lisa.helper.Android10DownloadFactory22
import ceui.lisa.helper.SAFactory
import ceui.lisa.utils.Settings
import ceui.pixiv.analytics.Analytics
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.Downloads
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.StageStore
import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.model.RelativePath
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DownloadPublicationTest.TestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadPublicationTest {
    class TestApplication : Shaft() { override fun onCreate() = Unit }
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication() as TestApplication
    private lateinit var db: AppDatabase
    private lateinit var manager: Manager
    private lateinit var item: DownloadItem

    @Before fun setUp() {
        Utils.init(context)
        Shaft.sSettings = Settings().apply { downloadLimitType = 2 }
        Shaft.sGson = Gson()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", context)
        ReflectionHelpers.setField(context, "analytics", Analytics(context))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach {
            ShadowStatFs.registerStats(it.path, 1_000_000, 1_000_000, 1_000_000)
        }
        manager = ReflectionHelpers.callConstructor(Manager::class.java)
        ReflectionHelpers.setField(manager, "content", java.util.concurrent.CopyOnWriteArrayList<DownloadItem>())
        ReflectionHelpers.setField(manager, "mDownloadOkHttpClient", OkHttpClient.Builder().addInterceptor {
            error("完整 stage 重试不应访问网络")
        }.build())
        item = DownloadItem(Illust(id = 2468, title = "publication", type = "illust", page_count = 1), 0).apply {
            url = "https://example.test/2468_p0.png"
            isSilent = true
            state = DownloadItem.DownloadState.DOWNLOADING
        }
    }

    @After fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        StageStore.clear(stageDir(), StageStore.keyForUrl(item.url))
        DownloadsRegistry.invalidateBackends()
        AppDatabase.destroyInstance()
        db.close()
    }

    @Test fun `both factories propagate publication failure and allow abort afterwards`() {
        for (saf in listOf(false, true)) {
            val backend = Sink().apply { failPublish = true }
            val factory = factory(saf, backend)
            factory.insert()
            assertThrows(SecurityException::class.java) { factory.finishWrite() }
            factory.abandonWrite()
            factory.abandonWrite()
            assertEquals(1, backend.aborts)
            assertFalse(backend.file.exists())
        }
    }

    @Test fun `successful publication is idempotent and cannot be abandoned`() {
        for (saf in listOf(false, true)) {
            val backend = Sink()
            val factory = factory(saf, backend)
            factory.insert()
            factory.finishWrite()
            factory.finishWrite()
            factory.abandonWrite()
            assertEquals(1, backend.finishes)
            assertEquals(0, backend.aborts)
            assertTrue(backend.file.exists())
        }
    }

    @Test fun `publication failure keeps failed task and stage without a completed record`() {
        val backend = Sink().apply { failPublish = true }
        seedStage()
        runChain(factory(false, backend))
        awaitFinished()
        assertEquals(DownloadItem.DownloadState.FAILED, item.state)
        assertTrue(manager.contentSnapshot().contains(item))
        assertNull(db.downloadDao().getDownloadByFileName(item.name))
        assertTrue(StageStore.partFile(stageDir(), StageStore.keyForUrl(item.url)).isFile)
        assertEquals(1, backend.aborts)
        assertFalse(backend.file.exists())

        // 新工厂复用完整 stage，网络拦截器会拒绝任何多余请求。
        item.state = DownloadItem.DownloadState.DOWNLOADING
        val retry = Sink()
        startChain(factory(false, retry))
        awaitFinished()
        assertEquals(DownloadItem.DownloadState.SUCCESS, item.state)
        assertFalse(manager.contentSnapshot().contains(item))
        assertNotNull(db.downloadDao().getDownloadByFileName(item.name))
        assertFalse(StageStore.partFile(stageDir(), StageStore.keyForUrl(item.url)).exists())
    }

    @Test fun `unpublished task remains active while publication is blocked`() {
        val backend = Sink().apply { blockPublish = true }
        seedStage()
        try {
            runChain(factory(false, backend))
            assertTrue(backend.entered.await(10, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(manager.contentSnapshot().contains(item))
            assertNull(db.downloadDao().getDownloadByFileName(item.name))
        } finally {
            backend.release.countDown()
            awaitFinished()
        }
        assertEquals(DownloadItem.DownloadState.SUCCESS, item.state)
        assertFalse(manager.contentSnapshot().contains(item))
        assertNotNull(db.downloadDao().getDownloadByFileName(item.name))
        assertFalse(StageStore.partFile(stageDir(), StageStore.keyForUrl(item.url)).exists())
    }

    @Test fun `completed record failure cannot strand an already published task`() {
        val backend = Sink().apply { blockPublish = true }
        seedStage()
        try {
            runChain(factory(false, backend))
            assertTrue(backend.entered.await(10, TimeUnit.SECONDS))
            db.close()
        } finally {
            backend.release.countDown()
            awaitFinished()
        }
        assertEquals(DownloadItem.DownloadState.SUCCESS, item.state)
        assertFalse(manager.contentSnapshot().contains(item))
        assertEquals(0, backend.aborts)
        assertTrue(backend.file.exists())
    }

    @Test fun `cancelled MediaStore publication failure still cleans target and permits stage retry`() {
        verifyCancelledPublicationFailure(saf = false)
    }

    @Test fun `cancelled SAF publication failure still cleans target and permits stage retry`() {
        verifyCancelledPublicationFailure(saf = true)
    }

    private fun verifyCancelledPublicationFailure(saf: Boolean) {
        val backend = Sink().apply { blockPublish = true; failPublish = true }
        seedStage()
        runChain(factory(saf, backend))
        val handles = ReflectionHelpers.getField<ConcurrentHashMap<String, DownloadTask>>(manager, "handles")
        val task = handles[item.uuid]!!
        try {
            assertTrue(backend.entered.await(10, TimeUnit.SECONDS))
            manager.stopOne(item.uuid)
        } finally {
            backend.release.countDown()
            // cancel 会提前移除 handle 并执行 onFinally，必须等消费线程真正退出再断言。
            val lock = ReflectionHelpers.getField<Any>(task, "interruptLock")
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            fun exited() = synchronized(lock) { ReflectionHelpers.getField<Int>(task, "phase") == 2 }
            while (!exited() && System.nanoTime() < until) Thread.sleep(5)
            assertTrue("取消后的发布回调必须收尾", exited())
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(1, backend.aborts)
        assertFalse(backend.file.exists())
        assertEquals(DownloadItem.DownloadState.PAUSED, item.state)
        assertTrue(manager.contentSnapshot().contains(item))
        assertNull(db.downloadDao().getDownloadByFileName(item.name))
        assertTrue(StageStore.partFile(stageDir(), StageStore.keyForUrl(item.url)).isFile)

        item.isPaused = false
        item.state = DownloadItem.DownloadState.DOWNLOADING
        val retry = Sink()
        startChain(factory(saf, retry))
        awaitFinished()
        assertEquals(DownloadItem.DownloadState.SUCCESS, item.state)
        assertFalse(manager.contentSnapshot().contains(item))
        assertNotNull(db.downloadDao().getDownloadByFileName(item.name))
        assertFalse(StageStore.partFile(stageDir(), StageStore.keyForUrl(item.url)).exists())
    }

    private fun factory(saf: Boolean, backend: Sink): DownloadFileFactory {
        val plan = Downloads.Plan(DownloadItems.illustPage(item.illust, item.index, item.url),
            RelativePath.parse("publication.png"), backend, OverwritePolicy.Replace, false)
        return if (saf) SAFactory(context, item).also { ReflectionHelpers.setField(it, "mPlan", plan) }
        else Android10DownloadFactory22(context, item).also { ReflectionHelpers.setField(it, "plan", plan) }
    }

    private fun stageDir() = File(context.cacheDir, StageStore.STAGE_DIR_NAME)
    private fun seedStage() {
        // 有效的 1×1 PNG，走真实 stage 校验与提交，仅隔离最终存储回调。
        val bytes = ceui.pixiv.download.repairablePngBytes().first
        val key = StageStore.keyForUrl(item.url)
        stageDir().mkdirs()
        StageStore.partFile(stageDir(), key).writeBytes(bytes)
        StageStore.writeManifest(StageStore.metaFile(stageDir(), key), StageStore.buildManifest(item.url, null, null, bytes.size.toLong()))
    }

    private fun runChain(factory: DownloadFileFactory) {
        ReflectionHelpers.getField<MutableList<DownloadItem>>(manager, "content").add(item)
        startChain(factory)
    }

    private fun startChain(factory: DownloadFileFactory) {
        val method = Manager::class.java.getDeclaredMethod("startDownloadChain", Context::class.java,
            DownloadItem::class.java, DownloadFileFactory::class.java, Uri::class.java, String::class.java,
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        method.invoke(manager, context, item, factory, null, item.url, 0L, true)
    }

    private fun awaitFinished() {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val handles = ReflectionHelpers.getField<ConcurrentHashMap<String, *>>(manager, "handles")
        while (handles.isNotEmpty() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("下载任务必须收尾", handles.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private inner class Sink : StorageBackend {
        val file = temp.newFile()
        var failPublish = false
        var blockPublish = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var finishes = 0
        var aborts = 0
        override fun exists(relPath: RelativePath) = false
        override fun delete(relPath: RelativePath) = file.delete()
        override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle =
            StorageBackend.WriteHandle(Uri.fromFile(file), file.outputStream(), onFinish = {
                finishes++
                entered.countDown()
                if (blockPublish) check(release.await(10, TimeUnit.SECONDS))
                if (failPublish) throw SecurityException("provider rejected publication")
            }, onAbort = { aborts++; file.delete() })
    }
}
