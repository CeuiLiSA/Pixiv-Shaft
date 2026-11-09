package ceui.pixiv.ui.upscale

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.MutableLiveData
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class AiResultCleanupTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context
    private val illustId = 1341L

    @Before fun setUp() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
    }

    @After fun tearDown() {
        IllustAiStore.clear(illustId)
        // Drain singleton LiveData posts before Robolectric replaces this test's main looper.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `regenerated upscale at the same path is a new result`() {
        val file = result("upscale-cache/result.png")
        val state = IllustAiStore.getOrCreate(illustId, 0)
        state.upscaleStatus = UpscaleStatus.Done
        state.upscaleResultPath = file.absolutePath
        state.acknowledgeResult()

        assertTrue(AiResultStorage.clear(context, true, false, false))
        IllustAiStore.pruneMissingResults()
        assertNull(state.upscaleResultPath)
        file.writeText("regenerated")
        state.upscaleResultPath = file.absolutePath
        state.upscaleStatus = UpscaleStatus.Done

        assertTrue(state.hasUnacknowledgedResult)
    }

    @Test fun `regenerated background removal at the same path is a new result`() {
        val file = result("rembg-cache/result.png")
        val state = IllustAiStore.getOrCreate(illustId, 0)
        state.rembgStatus = UpscaleStatus.Done
        state.rembgResultPath = file.absolutePath
        state.acknowledgeResult()

        assertTrue(AiResultStorage.clear(context, false, true, false))
        IllustAiStore.pruneMissingResults()
        assertNull(state.rembgResultPath)
        file.writeText("regenerated")
        state.rembgResultPath = file.absolutePath
        state.rembgStatus = UpscaleStatus.Done

        assertTrue(state.hasUnacknowledgedResult)
    }

    @Test fun `clearing one category does not prompt again for an acknowledged retained result`() {
        val upscale = result("upscale-cache/result.png")
        val rembg = result("rembg-cache/result.png")
        val state = IllustAiStore.getOrCreate(illustId, 0)
        state.upscaleResultPath = upscale.absolutePath
        state.rembgResultPath = rembg.absolutePath
        state.acknowledgeResult()

        assertTrue(AiResultStorage.clear(context, true, false, false))
        IllustAiStore.pruneMissingResults()

        assertEquals(rembg.absolutePath, state.rembgResultPath)
        assertFalse(state.hasUnacknowledgedResult)
    }

    @Test fun `cleanup preserves a new upscale preparation or queued task`() {
        for (status in listOf(UpscaleStatus.Preparing, UpscaleStatus.Queued)) {
            val file = result("upscale-cache/result.png")
            val state = IllustAiStore.getOrCreate(illustId, 0)
            state.upscaleResultPath = file.absolutePath
            assertTrue(AiResultStorage.clear(context, true, false, false))
            // The user can leave settings and start again before the cleanup callback arrives.
            state.upscaleStatus = status

            IllustAiStore.pruneMissingResults()

            assertNull(state.upscaleResultPath)
            assertEquals(status, state.upscaleStatus)
            assertTrue(state.isWorking)
        }
    }

    @Test fun `cleanup preserves a new background removal preparation or queued task`() {
        for (status in listOf(UpscaleStatus.Preparing, UpscaleStatus.Queued)) {
            val file = result("rembg-cache/result.png")
            val state = IllustAiStore.getOrCreate(illustId, 0)
            state.rembgResultPath = file.absolutePath
            assertTrue(AiResultStorage.clear(context, false, true, false))
            state.rembgStatus = status

            IllustAiStore.pruneMissingResults()

            assertNull(state.rembgResultPath)
            assertEquals(status, state.rembgStatus)
            assertTrue(state.isWorking)
        }
    }

    @Test fun `all category selections delete only their generated images`() {
        for (selection in 0..7) {
            val upscale = result("upscale-cache/result.PNG")
            val rembg = result("rembg-cache/result.png")
            val manga = File(context.cacheDir, "manga_translated_p0_12345.png").apply { writeText("manga") }
            val protected = listOf(
                result("upscale-cache/model.bin"),
                result("upscale-cache/nested/keep.png"),
                result("rembg-cache/model.param"),
                result("models/model.onnx"),
                result("gallery/saved.png"),
                File(context.cacheDir, "manga_translated_p0_12345.png.tmp").apply { writeText("temporary") },
                File(context.cacheDir, "ordinary.png").apply { writeText("ordinary") },
            )
            val stats = AiResultStorage.scan(context)
            assertEquals(3, stats.totalCount)
            assertEquals(upscale.length() + rembg.length() + manga.length(), stats.totalBytes)

            assertTrue(AiResultStorage.clear(context, selection and 1 != 0, selection and 2 != 0, selection and 4 != 0))

            assertEquals(selection and 1 == 0, upscale.exists())
            assertEquals(selection and 2 == 0, rembg.exists())
            assertEquals(selection and 4 == 0, manga.exists())
            protected.forEach { assertTrue("Should retain $it", it.exists()) }
        }
    }

    @Test fun `tasks submitted during cleanup wait and resume in queue order`() {
        val executed = mutableListOf<String>()
        fun task(key: String) = object : AiQueueItem {
            override val taskKey = key
            override val status = MutableLiveData(UpscaleStatus.Idle)
            override fun markQueued() { status.value = UpscaleStatus.Queued }
            override fun execute() { executed += key }
        }
        val first = task("cleanup-first")
        val removed = task("cleanup-removed")
        val last = task("cleanup-last")
        assertTrue(AiTaskQueue.beginResultCleanup())
        try {
            AiTaskQueue.enqueue(first)
            AiTaskQueue.enqueue(removed)
            AiTaskQueue.enqueue(last)
            AiTaskQueue.remove(removed.taskKey)
            assertTrue(executed.isEmpty())
            assertEquals(UpscaleStatus.Queued, first.status.value)
            assertFalse(AiTaskQueue.beginResultCleanup())

            AiTaskQueue.endResultCleanup()
            assertEquals(listOf(first.taskKey), executed)
            assertFalse(AiTaskQueue.beginResultCleanup())
            AiTaskQueue.onTaskFinished(first)
            assertEquals(listOf(first.taskKey, last.taskKey), executed)
            AiTaskQueue.onTaskFinished(last)
            assertTrue(AiTaskQueue.beginResultCleanup())
        } finally {
            listOf(first, removed, last).forEach { AiTaskQueue.remove(it.taskKey) }
            AiTaskQueue.endResultCleanup()
        }
    }

    private fun result(path: String): File = File(context.filesDir, path).apply {
        parentFile!!.mkdirs()
        writeText("result")
    }
}
