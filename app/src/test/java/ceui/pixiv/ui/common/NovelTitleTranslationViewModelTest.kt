package ceui.pixiv.ui.common

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelStore
import ceui.pixiv.ui.translate.AiTranslatePhase
import ceui.pixiv.ui.translate.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NovelTitleTranslationViewModelTest {
    @get:Rule val executor = InstantTaskExecutorRule()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var model: NovelTitleTranslationViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        model = NovelTitleTranslationViewModel()
        store.put("titles", model)
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private class Engine(var reply: suspend (List<String>, String) -> List<String> = { items, lang -> items.map { "$lang:$it" } }) : Translator {
        val batches = mutableListOf<List<String>>()
        override suspend fun translate(input: String, outputLang: String, onPhase: ((AiTranslatePhase) -> Unit)?): String = error("Use batches")
        override suspend fun translateBatch(
            inputs: List<String>, outputLang: String, onItem: ((Int, String) -> Unit)?,
            onProgress: ((Int, Int) -> Unit)?, onPhase: ((AiTranslatePhase) -> Unit)?, onRequestSent: (() -> Unit)?,
        ): List<String> {
            batches += inputs.toList()
            return reply(inputs, outputLang)
        }
    }

    @Test fun `only a manual request translates and duplicate titles are charged once`() = runTest(dispatcher) {
        val engine = Engine()
        assertEquals("原文", model.title("原文", "en"))
        assertTrue(engine.batches.isEmpty())
        model.translate(listOf("原文", "原文", " "), "en", engine)
        // Repeated taps while running must not issue another request.
        model.translate(listOf("原文"), "en", engine)
        advanceUntilIdle()
        assertEquals(listOf(listOf("原文")), engine.batches)
        assertEquals("en:原文", model.title("原文", "en"))
        assertEquals("新标题", model.title("新标题", "en"))
        model.hide()
        assertEquals("原文", model.title("原文", "en"))
        model.translate(listOf("原文", "新标题"), "en", engine)
        advanceUntilIdle()
        assertEquals(listOf(listOf("原文"), listOf("新标题")), engine.batches)
    }

    @Test fun `language or engine change cannot reuse stale translations`() = runTest(dispatcher) {
        val engine = Engine()
        model.translate(listOf("原文"), "en", engine)
        advanceUntilIdle()
        assertEquals("原文", model.title("原文", "ko"))
        model.translate(listOf("原文"), "ko", engine)
        advanceUntilIdle()
        assertEquals("ko:原文", model.title("原文", "ko"))
        val other = Engine { items, _ -> items.map { "new:$it" } }
        model.translate(listOf("原文"), "ko", other)
        advanceUntilIdle()
        assertEquals("new:原文", model.title("原文", "ko"))
        assertEquals(1, other.batches.size)
    }

    @Test fun `partial failure preserves translated titles and retries only missing ones`() = runTest(dispatcher) {
        val engine = Engine { _, _ -> listOf("译文", "") }
        model.translate(listOf("a", "b"), "en", engine)
        advanceUntilIdle()
        assertTrue(model.state.value?.error?.peekContent() is IOException)
        assertEquals("译文", model.title("a", "en"))
        assertEquals("b", model.title("b", "en"))
        engine.reply = { items, _ -> items.map { "完成" } }
        model.translate(listOf("a", "b"), "en", engine)
        advanceUntilIdle()
        assertEquals(listOf("b"), engine.batches.last())
        assertNull(model.state.value?.error)
    }

    @Test fun `restore original cancels a request and prevents late results`() = runTest(dispatcher) {
        var cancelled = false
        val engine = Engine { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        model.translate(listOf("a"), "en", engine)
        runCurrent()
        model.hide()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertFalse(model.shown)
        assertFalse(model.state.value!!.running)
        assertNull(model.state.value?.error)
        assertEquals("a", model.title("a", "en"))
    }

    @Test fun `wrong result count cannot replace unrelated titles`() = runTest(dispatcher) {
        model.translate(listOf("a", "b"), "en", Engine { _, _ -> listOf("one") })
        advanceUntilIdle()
        assertTrue(model.state.value?.error?.peekContent() is IOException)
        assertEquals("a", model.title("a", "en"))
        assertEquals("b", model.title("b", "en"))
    }

    @Test fun `switching engine during a request cancels the old task before publishing the new result`() = runTest(dispatcher) {
        var cancelled = false
        val slow = Engine { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        model.translate(listOf("a"), "en", slow)
        runCurrent()
        model.translate(listOf("a"), "en", Engine { _, _ -> listOf("new engine") })
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals("new engine", model.title("a", "en"))
        assertNull(model.state.value?.error)
    }
}
