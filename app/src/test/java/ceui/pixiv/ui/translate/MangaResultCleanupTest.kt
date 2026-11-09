package ceui.pixiv.ui.translate

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ceui.pixiv.api.model.Illust
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class MangaResultCleanupTest {
    private fun center(): MangaBatchTranslateCenter {
        val app = RuntimeEnvironment.getApplication()
        return MangaBatchTranslateCenter(app, MangaTranslateModels(app))
    }

    @Test fun `cleanup waits for cancelled batch to finish background work`() {
        val center = center()
        val finishWrite = CompletableDeferred<Unit>()
        // A blocking PNG writer keeps running until its IO block returns, even after cancellation.
        val batch = CoroutineScope(Dispatchers.Unconfined).launch {
            withContext(NonCancellable) { finishWrite.await() }
        }
        ReflectionHelpers.setField(center, "job", batch)
        batch.cancel()
        assertFalse(batch.isActive)
        assertFalse(batch.isCompleted)

        try {
            assertFalse(center.beginResultCleanup())
        } finally {
            center.endResultCleanup()
            finishWrite.complete(Unit)
        }

        assertTrue(batch.isCompleted)
        assertTrue(center.beginResultCleanup())
        center.endResultCleanup()
    }

    @Test fun `cleanup excludes single page translation and prunes only deleted outputs`() {
        val center = center()
        val app = RuntimeEnvironment.getApplication()
        val removed = File(app.cacheDir, "manga_translated_p0_12345.png").apply { writeText("removed") }
        val retained = File(app.cacheDir, "manga_translated_p1_12345.png").apply { writeText("retained") }
        center.publish(1L, 0, removed.absolutePath)
        center.publish(1L, 1, retained.absolutePath)
        assertTrue(center.beginSinglePage())
        assertFalse(center.beginResultCleanup())
        center.endSinglePage()

        assertTrue(center.beginResultCleanup())
        try {
            assertFalse(center.beginSinglePage())
            assertTrue(removed.delete())
            center.pruneMissingResults()
            assertEquals(mapOf(1 to retained.absolutePath), center.pathsOf(1L).value)
        } finally {
            center.endResultCleanup()
            retained.delete()
        }
        assertTrue(center.beginSinglePage())
        center.endSinglePage()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `cancelling batch cannot be replaced while its writer is still finishing`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val center = center()
        val finishWrite = CompletableDeferred<Unit>()
        val batch = CoroutineScope(Dispatchers.Unconfined).launch {
            withContext(NonCancellable) { finishWrite.await() }
        }
        ReflectionHelpers.setField(center, "job", batch)
        batch.cancel()
        try {
            // Replacing this job lets the old batch's finally block erase the new job reference,
            // after which cleanup can no longer see the writer belonging to the new batch.
            assertFalse(center.start(Illust(id = 1341L), emptyList(),
                MangaOcrModel.MANGA_OCR_BASE, ComicTextDetectorModel.CTD_BASE))
            assertFalse(center.beginSinglePage())
            assertFalse(center.beginResultCleanup())
        } finally {
            ReflectionHelpers.getField<Job?>(center, "job")?.cancel()
            center.endSinglePage()
            center.endResultCleanup()
            finishWrite.complete(Unit)
            dispatcher.scheduler.runCurrent()
            Dispatchers.resetMain()
        }
    }
}
