package ceui.pixiv.ui.novel.reader

import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.translate.AiTranslatePhase
import ceui.pixiv.ui.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NovelBodyTranslatorTest {
    private class Engine(val reply: suspend (List<String>) -> List<String>) : Translator {
        val batches = mutableListOf<List<String>>()
        override suspend fun translate(input: String, outputLang: String, onPhase: ((AiTranslatePhase) -> Unit)?): String =
            error("AI paragraphs must use the batch protocol")

        override suspend fun translateBatch(
            inputs: List<String>, outputLang: String, onItem: ((Int, String) -> Unit)?,
            onProgress: ((Int, Int) -> Unit)?, onPhase: ((AiTranslatePhase) -> Unit)?, onRequestSent: (() -> Unit)?,
        ): List<String> {
            batches += inputs.toList()
            return reply(inputs)
        }
    }

    private fun paragraph(start: Int, text: String) = ContentToken.Paragraph(start, start + text.length, text)

    @Test fun `AI preserves paragraph boundaries and embedded newlines`() = runBlocking {
        val engine = Engine { listOf("甲\n乙", "丙") }
        val result = mutableMapOf<Int, String>()
        NovelBodyTranslator.translate(listOf(paragraph(100, "a\nb"), paragraph(200, "c")), "zh-CN", engine) {
            result.putAll(it)
        }
        assertEquals(listOf(listOf("a\nb", "c")), engine.batches)
        assertEquals(mapOf(100 to "甲\n乙", 200 to "丙"), result)
    }

    @Test fun `partial AI result keeps successful paragraphs and leaves missing ones retryable`() = runBlocking {
        val paragraphs = listOf(paragraph(0, "a"), paragraph(10, "b"))
        val result = mutableMapOf<Int, String>()
        try {
            NovelBodyTranslator.translate(paragraphs, "zh-CN", Engine { listOf("甲", " ") }) { result.putAll(it) }
            fail("A missing paragraph must not be reported as done")
        } catch (_: IOException) { }
        assertEquals(mapOf(0 to "甲"), result)
        val retry = Engine { listOf("乙") }
        NovelBodyTranslator.translate(paragraphs.filter { it.sourceStart !in result }, "zh-CN", retry) { result.putAll(it) }
        assertEquals(listOf(listOf("b")), retry.batches)
        assertEquals(mapOf(0 to "甲", 10 to "乙"), result)
    }

    @Test fun `network failure stops chunks and preserves completed text`() = runBlocking {
        val engine = Engine { if (it.first() == "b".repeat(2500)) throw IOException("offline") else listOf("甲") }
        val result = mutableMapOf<Int, String>()
        try {
            NovelBodyTranslator.translate(
                listOf(paragraph(0, "a".repeat(2500)), paragraph(3000, "b".repeat(2500)), paragraph(6000, "c".repeat(2500))),
                "zh-CN", engine,
            ) { result.putAll(it) }
            fail("Expected network failure")
        } catch (_: IOException) { }
        assertEquals(2, engine.batches.size)
        assertEquals(mapOf(0 to "甲"), result)
    }

    @Test fun `wrong paragraph count cannot shift translations onto another paragraph`() = runBlocking {
        try {
            NovelBodyTranslator.translate(listOf(paragraph(0, "a"), paragraph(10, "b")), "en", Engine { listOf("one") }) {
                fail("No incorrectly aligned result may be published")
            }
            fail("Expected count mismatch")
        } catch (_: IOException) { }
    }

    @Test fun `cancellation propagates without publishing a result`() = runBlocking {
        try {
            NovelBodyTranslator.translate(listOf(paragraph(0, "a")), "en", Engine { throw CancellationException("cancel") }) {
                fail("Cancelled request must not publish")
            }
            fail("Expected cancellation")
        } catch (e: CancellationException) { assertEquals("cancel", e.message) }
    }
}
