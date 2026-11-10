package ceui.lisa.core

import android.app.Application
import android.net.Uri
import android.os.Looper
import ceui.lisa.activities.Shaft
import ceui.lisa.file.LegacyFile
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ManagerDirectTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var manager: Manager
    private val bodies = mutableListOf<PausedBody>()

    @Before fun setUp() {
        Utils.init(context)
        Shaft.sSettings = Settings().apply { downloadLimitType = 2 }
        Shaft.sGson = Gson()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", context)
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach {
            ShadowStatFs.registerStats(it.path, 1_000_000, 1_000_000, 1_000_000)
        }
        manager = ReflectionHelpers.callConstructor(Manager::class.java)
    }

    @After fun tearDown() {
        manager.stopAll()
        bodies.forEach { it.release.countDown() }
        bodies.forEach { assertTrue(it.closed.await(5, TimeUnit.SECONDS)) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `target removed after range request cannot complete with only the tail`() {
        missingTargetCase(knownTotal = true)
    }

    @Test fun `unknown response length still requires the resumed prefix on disk`() {
        missingTargetCase(knownTotal = false)
    }

    private fun missingTargetCase(knownTotal: Boolean) {
        val file = temp.newFile().also { it.writeText("ABC") }
        val emitter = RecordingEmitter()
        val client = client { request ->
            assertEquals("bytes=3-", request.header("Range"))
            assertTrue(file.delete())
            val body = if (knownTotal) "DEFGH".toResponseBody() else object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = -1L
                override fun source(): BufferedSource = Buffer().writeUtf8("DEFGH")
            }
            response(request, 206, body, if (knownTotal) "bytes 3-7/8" else "bytes 3-7/*")
        }
        val error = assertThrows(IOException::class.java) { transfer(file, 3L, client, emitter) }
        assertTrue(error.message!!.contains("direct file length mismatch"))
        assertFalse(emitter.completed)
        assertTrue(emitter.values.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun `valid partial zip appends its tail and remains readable`() {
        val frame = "frame bytes".repeat(100).toByteArray()
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("frame.jpg")); zip.write(frame); zip.closeEntry()
            }
        }.toByteArray()
        val offset = 30
        val file = temp.newFile().also { it.writeBytes(bytes.copyOf(offset)) }
        val emitter = RecordingEmitter()
        transfer(file, offset.toLong(), client { request ->
            response(request, 206, bytes.copyOfRange(offset, bytes.size).toResponseBody(),
                "bytes $offset-${bytes.lastIndex}/${bytes.size}")
        }, emitter)
        assertTrue(emitter.completed)
        assertArrayEquals(bytes, file.readBytes())
        ZipInputStream(file.inputStream()).use { zip ->
            assertNotNull(zip.nextEntry)
            assertArrayEquals(frame, zip.readBytes())
        }
    }

    @Test fun `server ignoring range replaces the partial file`() {
        val file = temp.newFile().also { it.writeText("ABC") }
        val emitter = RecordingEmitter()
        transfer(file, 3L, client { response(it, 200, "ABCDEFGH".toResponseBody()) }, emitter)
        assertTrue(emitter.completed)
        assertEquals("ABCDEFGH", file.readText())
    }

    @Test fun `wrong range discards the partial so the next attempt starts fresh`() {
        val file = temp.newFile().also { it.writeText("ABC") }
        val emitter = RecordingEmitter()
        assertThrows(IOException::class.java) {
            transfer(file, 3L, client { response(it, 206, "EFGH".toResponseBody(), "bytes 4-7/8") }, emitter)
        }
        assertFalse(emitter.completed)
        assertFalse(file.exists())
    }

    @Test fun `short response is not completed`() {
        val file = temp.newFile().also { it.writeText("ABC") }
        val emitter = RecordingEmitter()
        assertThrows(IOException::class.java) {
            transfer(file, 3L, client { response(it, 206, "DEFG".toResponseBody(), "bytes 3-7/8") }, emitter)
        }
        assertFalse(emitter.completed)
    }

    @Test fun `pause after the final byte accepts 416 for a complete persisted file`() {
        val file = temp.newFile().also { it.writeText("ABCDEFGH") }
        val emitter = RecordingEmitter()
        transfer(file, 8L, client { request ->
            assertEquals("bytes=8-", request.header("Range"))
            response(request, 416, "".toResponseBody(), "bytes */8")
        }, emitter)
        assertTrue(emitter.completed)
        assertEquals("ABCDEFGH", file.readText())
    }

    @Test fun `416 cannot complete a full length offset whose file disappeared`() {
        val file = temp.newFile().also { it.writeText("ABCDEFGH") }
        val emitter = RecordingEmitter()
        assertThrows(IOException::class.java) {
            transfer(file, 8L, client { request ->
                assertTrue(file.delete())
                response(request, 416, "".toResponseBody(), "bytes */8")
            }, emitter)
        }
        assertFalse(emitter.completed)
        assertFalse(file.exists())
    }

    @Test fun `contradictory 416 discards the partial so the next attempt starts fresh`() {
        val file = temp.newFile().also { it.writeText("ABCDEFGHI") }
        val emitter = RecordingEmitter()
        assertThrows(IOException::class.java) {
            transfer(file, 9L, client { response(it, 416, "".toResponseBody(), "bytes */8") }, emitter)
        }
        assertFalse(emitter.completed)
        assertFalse(file.exists())
    }

    @Test fun `pause and startOne rebuilds the gif factory and sends the actual zip offset`() {
        val item = item()
        val file = LegacyFile.gifZipFile(context, item.illust).also { it.writeBytes(byteArrayOf()) }
        val requests = LinkedBlockingQueue<Request>()
        val first = PausedBody("ABC", 8L).also { bodies.add(it) }
        val second = PausedBody("DEFGH", 5L).also { bodies.add(it) }
        val client = client { request ->
            val attempt = requests.size
            requests.add(request)
            if (attempt == 0) response(request, 200, first)
            else response(request, 206, second, "bytes 3-7/8")
        }
        ReflectionHelpers.setField(manager, "mDownloadOkHttpClient", client)
        ReflectionHelpers.getField<MutableList<DownloadItem>>(manager, "content").add(item)
        manager.startOne(item.uuid)
        await { first.blocked.count == 0L }
        assertEquals("ABC", file.readText())
        assertNull(requests.peek()!!.header("Range"))
        manager.stopOne(item.uuid)
        assertTrue(first.closed.await(5, TimeUnit.SECONDS))
        manager.startOne(item.uuid)
        await { requests.size == 2 }
        assertEquals("bytes=3-", requests.toList()[1].header("Range"))
        await { second.blocked.count == 0L }
        assertEquals("ABCDEFGH", file.readText())
        manager.stopOne(item.uuid)
    }

    private fun item() = DownloadItem(Illust(id = 1354L, title = "resume-test", type = "ugoira", page_count = 1), 0)
        .apply { url = "https://example.test/ugoira.zip"; isSilent = true }

    // 调用真实传输方法，仅替换网络响应；成功和失败仍由生产代码判断。
    private fun transfer(file: File, offset: Long, client: OkHttpClient, emitter: RecordingEmitter) {
        val method = Manager::class.java.getDeclaredMethod("runDirectTransfer", android.content.Context::class.java,
            DownloadItem::class.java, File::class.java, Uri::class.java, String::class.java,
            Long::class.javaPrimitiveType, OkHttpClient::class.java, DownloadEmitter::class.java)
        method.isAccessible = true
        try {
            method.invoke(manager, context, item(), null, Uri.fromFile(file), "https://example.test/file.zip", offset, client, emitter)
        } catch (e: InvocationTargetException) { throw e.targetException }
    }

    private fun client(reply: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build()

    private fun response(request: Request, code: Int, body: ResponseBody, range: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body)
            .apply { if (range != null) header("Content-Range", range) }.build()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Download operation did not reach the expected state" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private class RecordingEmitter : DownloadEmitter {
        val values = mutableListOf<String>()
        var completed = false
        override fun onNext(value: String) { values.add(value) }
        override fun onComplete() { completed = true }
        override fun onError(error: Throwable) { throw AssertionError(error) }
        override fun tryOnError(error: Throwable) = onError(error)
        override fun isDisposed(): Boolean = false
    }

    private class PausedBody(text: String, private val size: Long) : ResponseBody() {
        val blocked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var sent = false
        private val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!sent) { sent = true; sink.writeUtf8(text); return text.length.toLong() }
                blocked.countDown()
                try { release.await() } catch (e: InterruptedException) { throw IOException("paused", e) }
                return -1L
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed.countDown() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = size
        override fun source(): BufferedSource = source
    }
}
