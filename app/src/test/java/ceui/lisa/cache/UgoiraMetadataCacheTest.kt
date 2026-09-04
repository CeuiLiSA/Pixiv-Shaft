package ceui.lisa.cache

import ceui.lisa.models.FramesBean
import ceui.lisa.models.GifResponse
import ceui.lisa.models.ImageUrlsBean
import ceui.lisa.models.UgoiraMetadataBean
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class UgoiraMetadataCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        cacheRoot = temporaryFolder.newFolder("cache")
    }

    @Test
    fun `new entries round-trip through versioned JSON`() {
        val store = UgoiraMetadataDiskCache(cacheRoot)

        store.put(42L, sampleResponse())

        val file = currentFile(42L)
        assertTrue(file.isFile)
        val json = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(1, json.get("version").asInt)
        val header = file.inputStream().use { it.readNBytes(JAVA_MAGIC.size) }
        assertFalse("Java serialization header must be gone", header.contentEquals(JAVA_MAGIC))

        val restored = UgoiraMetadataDiskCache(cacheRoot).get(42L)
        assertResponse(restored)
    }

    @Test
    fun `legacy Java serialization is read once and migrated`() {
        val legacy = File(cacheRoot, "illust id_42")
        legacy.writeBytes(Base64.getDecoder().decode(LEGACY_FIXTURE))

        val restored = UgoiraMetadataDiskCache(cacheRoot).get(42L)

        assertResponse(restored)
        assertFalse("legacy file is deleted only after the JSON commit", legacy.exists())
        assertTrue(currentFile(42L).isFile)
        assertResponse(UgoiraMetadataDiskCache(cacheRoot).get(42L))
    }

    @Test
    fun `legacy descriptors with R8 renamed classes are migrated by field shape`() {
        val legacy = File(cacheRoot, "illust id_42")
        val oldValue = RenamedGifResponse().apply {
            ugoira_metadata = RenamedMetadata().apply {
                zip_urls = RenamedUrls().apply {
                    medium = "https://i.pximg.net/sample.zip"
                }
                frames = arrayListOf(RenamedFrame().apply {
                    file = "000000.jpg"
                    delay = 120
                })
            }
        }
        ObjectOutputStream(FileOutputStream(legacy)).use { it.writeObject(oldValue) }

        val restored = UgoiraMetadataDiskCache(cacheRoot).get(42L)

        assertResponse(restored)
        assertFalse(legacy.exists())
        assertTrue(currentFile(42L).isFile)
    }

    @Test
    fun `legacy file is retained when migration cannot be committed`() {
        val legacy = File(cacheRoot, "illust id_42").apply {
            writeBytes(Base64.getDecoder().decode(LEGACY_FIXTURE))
        }
        File(cacheRoot, "ugoira_metadata").writeText("blocks directory creation")

        assertResponse(UgoiraMetadataDiskCache(cacheRoot).get(42L))
        assertTrue("a failed migration must remain retryable", legacy.isFile)
    }

    @Test
    fun `legacy reader rejects types outside the historical object graph`() {
        val legacy = File(cacheRoot, "illust id_42")
        ObjectOutputStream(FileOutputStream(legacy)).use { it.writeObject(File("unexpected")) }

        assertNull(UgoiraMetadataDiskCache(cacheRoot).get(42L))
        assertFalse("an unreadable cache should not fail on every future lookup", legacy.exists())
        assertFalse(currentFile(42L).exists())
    }

    @Test
    fun `corrupt entries are evicted and treated as a miss`() {
        val file = currentFile(42L).apply {
            parentFile!!.mkdirs()
            writeText("{broken")
        }

        assertNull(UgoiraMetadataDiskCache(cacheRoot).get(42L))
        assertFalse(file.exists())
    }

    @Test
    fun `unknown future format is preserved`() {
        val file = currentFile(42L).apply {
            parentFile!!.mkdirs()
            writeText("""{"version":2,"value":{}}""")
        }
        val futureEntry = file.readText()

        val store = UgoiraMetadataDiskCache(cacheRoot)
        assertNull(store.get(42L))
        store.put(42L, sampleResponse())

        assertTrue("a downgraded app must not destroy a newer cache format", file.isFile)
        assertEquals(futureEntry, file.readText())
    }

    private fun currentFile(illustId: Long) = File(cacheRoot, "ugoira_metadata/$illustId.json")

    private fun sampleResponse(): GifResponse {
        val frame = FramesBean().apply {
            file = "000000.jpg"
            delay = 120
        }
        val urls = ImageUrlsBean().apply {
            medium = "https://i.pximg.net/sample.zip"
        }
        val metadata = UgoiraMetadataBean().apply {
            zip_urls = urls
            frames = arrayListOf(frame)
        }
        return GifResponse().apply { ugoira_metadata = metadata }
    }

    private fun assertResponse(value: GifResponse?) {
        assertNotNull(value)
        val metadata = value!!.ugoira_metadata
        assertEquals("https://i.pximg.net/sample.zip", metadata.zip_urls.medium)
        assertEquals("000000.jpg", metadata.frames.single().file)
        assertEquals(120, metadata.frames.single().delay)
    }

    companion object {
        private val JAVA_MAGIC = byteArrayOf(0xAC.toByte(), 0xED.toByte())

        // 由重构前的 Cache/ObjectOutputStream 和模型类生成，固定住真实的旧磁盘协议。
        private const val LEGACY_FIXTURE =
            "rO0ABXNyABxjZXVpLmxpc2EubW9kZWxzLkdpZlJlc3BvbnNlsmiQJkIwd4cCAAFMAA91" +
                "Z29pcmFfbWV0YWRhdGF0ACVMY2V1aS9saXNhL21vZGVscy9VZ29pcmFNZXRhZGF0YUJl" +
                "YW47eHBzcgAjY2V1aS5saXNhLm1vZGVscy5VZ29pcmFNZXRhZGF0YUJlYW6kpSnfSNAr" +
                "1gIAAkwABmZyYW1lc3QAEExqYXZhL3V0aWwvTGlzdDtMAAh6aXBfdXJsc3QAIExjZXVp" +
                "L2xpc2EvbW9kZWxzL0ltYWdlVXJsc0JlYW47eHBzcgATamF2YS51dGlsLkFycmF5TGlz" +
                "dHiB0h2Zx2GdAwABSQAEc2l6ZXhwAAAAAXcEAAAAAXNyABtjZXVpLmxpc2EubW9kZWxz" +
                "LkZyYW1lc0JlYW4aaT/bCjAgPQIAAkkABWRlbGF5TAAEZmlsZXQAEkxqYXZhL2xhbmcv" +
                "U3RyaW5nO3hwAAAAeHQACjAwMDAwMC5qcGd4c3IAHmNldWkubGlzYS5tb2RlbHMuSW1h" +
                "Z2VVcmxzQmVhbuFSb7VF9+MGAgAETAAFbGFyZ2VxAH4ACkwABm1lZGl1bXEAfgAKTAAI" +
                "b3JpZ2luYWxxAH4ACkwADXNxdWFyZV9tZWRpdW1xAH4ACnhwcHQAHmh0dHBzOi8vaS5w" +
                "eGltZy5uZXQvc2FtcGxlLnppcHBw"
    }

    private class RenamedGifResponse : Serializable {
        @JvmField
        var ugoira_metadata: RenamedMetadata? = null
    }

    private class RenamedMetadata : Serializable {
        @JvmField
        var frames: ArrayList<RenamedFrame>? = null

        @JvmField
        var zip_urls: RenamedUrls? = null
    }

    private class RenamedFrame : Serializable {
        @JvmField
        var delay: Int = 0

        @JvmField
        var file: String? = null
    }

    private class RenamedUrls : Serializable {
        @JvmField
        var large: String? = null

        @JvmField
        var medium: String? = null

        @JvmField
        var original: String? = null

        @JvmField
        var square_medium: String? = null
    }
}
