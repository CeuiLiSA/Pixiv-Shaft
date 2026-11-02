package ceui.pixiv.feeds.cache

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileFeedCacheBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File by lazy { tmp.newFolder("feed_cache") }
    private val backend by lazy { FileFeedCacheBackend(root) }

    private fun record(savedAt: Long, payload: String = "{\"a\":1}", cursor: String? = "next") =
        FeedCacheRecord(1, payload, cursor, savedAt)

    private fun key(slot: String, uid: Long = 7L) = "$slot#$uid#com.example.Resp"

    @Test
    fun `round trips every field, including a payload with newlines and a null cursor`() = runBlocking {
        val withCursor = record(10L, payload = "{\n  \"a\": \"第一行\\n\"\n}\n", cursor = "c=1&d=2")
        backend.save(key("a"), withCursor)
        assertEquals(withCursor, backend.load(key("a")))

        val noCursor = record(11L, cursor = null)
        backend.save(key("b"), noCursor)
        assertEquals(noCursor, backend.load(key("b")))
    }

    @Test
    fun `envelope uses stable literal field names on disk`() = runBlocking {
        backend.save(key("a"), record(10L))

        val firstLine = root.listFiles()!!.single().readLines().first()
        assertEquals("{\"v\":1,\"cursor\":\"next\",\"savedAt\":10}", firstLine)
    }

    @Test
    fun `a slower older save does not overwrite a newer snapshot`() = runBlocking {
        backend.save(key("a"), record(20L, payload = "new"))
        backend.save(key("a"), record(10L, payload = "old"))

        assertEquals("new", backend.load(key("a"))?.payloadJson)
    }

    @Test
    fun `a corrupt file is a miss and gets deleted`() = runBlocking {
        backend.save(key("a"), record(10L))
        val file = root.listFiles()!!.single()
        file.writeText("not json\npayload")

        assertNull(backend.load(key("a")))
        assertFalse(file.exists())
    }

    @Test
    fun `keeps only the newest slots`() = runBlocking {
        for (i in 1..25) backend.save(key("s$i"), record(i.toLong()))

        assertEquals(24, root.listFiles()!!.size)
        assertNull(backend.load(key("s1")))
        assertNotNull(backend.load(key("s25")))
    }

    @Test
    fun `removeByAccount only removes that exact uid`() = runBlocking {
        backend.save(key("a", uid = 7L), record(1L))
        backend.save(key("a", uid = 77L), record(2L))

        backend.removeByAccount(7L)

        assertNull(backend.load(key("a", uid = 7L)))
        assertTrue(backend.load(key("a", uid = 77L)) != null)
    }
}
