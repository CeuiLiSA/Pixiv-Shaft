package ceui.pixiv.ui.translate

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
}
