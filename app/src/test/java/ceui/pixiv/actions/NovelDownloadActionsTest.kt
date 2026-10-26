package ceui.pixiv.actions

import ceui.lisa.activities.Shaft
import ceui.loxia.Novel
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NovelDownloadActionsTest {

    @Before
    fun setUp() {
        Shaft.sGson = Gson()
    }

    private fun parse(payload: String, type: String = PixivActionTypes.NOVEL_DOWNLOAD) =
        PendingAction(id = 1L, type = type, dedupeKey = "k", payload = payload, attempt = 0)

    @Test
    fun `selected chapters keep their series positions, not their index in the batch`() {
        val requests = novelDownloadRequests(
            novels = listOf(Novel(id = 30L), Novel(id = 50L), Novel(id = 90L)),
            format = ExportFormat.Epub,
            seriesPositions = mapOf(30L to 3, 50L to 5, 90L to 9),
            seriesTotal = 12,
        )

        val payloads = requests.map { parse(it.payload).parsePayload<NovelDownloadPayload>()!! }
        assertEquals(listOf(3, 5, 9), payloads.map { it.seriesOrder })
        assertEquals(listOf(12, 12, 12), payloads.map { it.seriesTotal })
        assertEquals(listOf(1, 2, 3), payloads.map { it.index })
        assertTrue(payloads.all { it.total == 3 && it.format == ExportFormat.Epub })
        assertEquals(1, payloads.map { it.batchId }.distinct().size)
        assertEquals("novel_download:30:Epub", requests.first().dedupeKey)
    }

    @Test
    fun `series total is dropped for novels without a series position`() {
        val request = novelDownloadRequests(
            novels = listOf(Novel(id = 7L)),
            format = ExportFormat.Txt,
            seriesPositions = emptyMap(),
            seriesTotal = 12,
        ).single()

        val payload = parse(request.payload).parsePayload<NovelDownloadPayload>()!!
        assertNull(payload.seriesOrder)
        assertNull(payload.seriesTotal)
    }

    @Test
    fun `unknown format from a newer build is a terminal failure, not a retry`() = runBlocking {
        val payload = """{"novel":{"id":1},"format":"Docx","batchId":"b","index":1,"total":1}"""
        val outcome = NovelDownloadHandler(isOnline = { true }).execute(parse(payload))
        assertTrue(outcome is ActionOutcome.Fail)

        val series = """{"seriesId":1,"format":"Docx"}"""
        val seriesOutcome = NovelSeriesDownloadHandler(isOnline = { true }, enqueue = {})
            .execute(parse(series, PixivActionTypes.NOVEL_SERIES_DOWNLOAD))
        assertTrue(seriesOutcome is ActionOutcome.Fail)
    }
}
