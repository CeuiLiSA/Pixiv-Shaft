package ceui.pixiv.ui.novel.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelTtsPayloadStoreTest {

    @Test
    fun payloadIsConsumedAfterServiceHandoff() {
        val payload = NovelTtsPayloadStore.Payload(
            sessionId = "novel:42",
            title = "A long novel",
            segments = NovelTtsText.split("x".repeat(100_000)),
            speed = 1.25f,
            pitch = 1f,
            engine = null,
            voice = null,
        )

        val id = NovelTtsPayloadStore.put(payload)

        assertEquals(payload, NovelTtsPayloadStore.take(id))
        assertNull(NovelTtsPayloadStore.take(id))
    }

    @Test
    fun newerStartReplacesAnUndeliveredPayload() {
        val first = NovelTtsPayloadStore.Payload(
            sessionId = "novel:1",
            title = "First",
            segments = listOf(NovelTtsText.Segment("first")),
            speed = 1f,
            pitch = 1f,
            engine = null,
            voice = null,
        )
        val second = first.copy(sessionId = "novel:2", title = "Second")

        val firstId = NovelTtsPayloadStore.put(first)
        val secondId = NovelTtsPayloadStore.put(second)

        assertNull(NovelTtsPayloadStore.take(firstId))
        assertEquals(second, NovelTtsPayloadStore.take(secondId))
    }
}
