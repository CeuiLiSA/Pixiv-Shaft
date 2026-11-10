package ceui.pixiv.ui.common

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ModelDownloadManagerTest {
    @Test fun `cancelled download finishes before retry reuses fixed zip`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val archive = zip()
        server.enqueue(
            MockResponse().setBody(Buffer().write(archive))
                .throttleBody(1024, 200, TimeUnit.MILLISECONDS),
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))
        val context = RuntimeEnvironment.getApplication()
        val model = TestModel(server.url("/model.zip").toString())
        val manager = object : ModelDownloadManager() {
            override val storageSubDir = "model-download-test"
            override val logTag = "ModelDownloadTest"
            override val readTimeoutSeconds = 3L
        }
        val firstProgress = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            manager.downloadModel(context, model) { bytesRead, _ ->
                if (bytesRead > 0) firstProgress.complete(Unit)
            }
        }
        try {
            withTimeout(10_000) { firstProgress.await() }
            first.cancel()
            val second = async(Dispatchers.Default) {
                manager.downloadModel(context, model) { _, _ -> }
            }
            withTimeout(15_000) {
                first.join()
                assertTrue(second.await())
            }
            assertTrue(manager.isModelReady(context, model))
            assertFalse(File(context.cacheDir, "model_dl_${model.assetDir}.zip").exists())
        } finally {
            first.cancelAndJoin()
            manager.deleteModel(context, model)
            server.shutdown()
        }
    }

    @Test fun `cancel stops a stalled connection without waiting for the read timeout`() = runBlocking {
        val server = MockWebServer()
        server.start()
        // 服务端收下请求后一个字节都不回：客户端卡在阻塞的 socket 读上，线程中断叫不醒它
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val context = RuntimeEnvironment.getApplication()
        val model = TestModel(server.url("/model.zip").toString())
        val manager = object : ModelDownloadManager() {
            override val storageSubDir = "model-download-test"
            override val logTag = "ModelDownloadTest"
            override val readTimeoutSeconds = 30L
        }
        val download = launch(Dispatchers.Default) {
            manager.downloadModel(context, model) { _, _ -> }
        }
        try {
            assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            delay(300)
            withTimeout(5_000) { download.cancelAndJoin() }
        } finally {
            download.cancelAndJoin()
            manager.deleteModel(context, model)
            server.shutdown()
        }
    }

    @Test fun `page entry cleans an incomplete model and interrupted temporary files`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val model = TestModel("http://localhost/unused.zip")
        val manager = object : ModelDownloadManager() {
            override val storageSubDir = "model-download-test"
            override val logTag = "ModelDownloadTest"
        }
        val dir = manager.modelDir(context, model)
        val staging = File(dir.parentFile, dir.name + ".staging")
        val download = File(context.cacheDir, "model_dl_${model.assetDir}.zip")
        val imported = File(context.cacheDir, "model_import_${model.assetDir}.zip")
        try {
            dir.mkdirs()
            File(dir, "encoder.onnx").writeText("partial")
            staging.mkdirs()
            File(staging, "encoder.onnx").writeText("partial")
            download.writeText("partial")
            imported.writeText("partial")
            assertFalse(manager.isModelReady(context, model))
            withTimeout(5_000) {
                while (listOf(dir, staging, download, imported).any { it.exists() }) delay(20)
            }
            assertFalse(manager.isModelReady(context, model))
        } finally {
            manager.deleteModel(context, model)
        }
    }

    private fun zip(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            val randomBytes = Random(42).nextBytes(50_000)
            out.putNextEntry(ZipEntry("encoder.onnx"))
            out.write(randomBytes)
            out.closeEntry()
            out.putNextEntry(ZipEntry("decoder.onnx"))
            out.write(byteArrayOf(1))
            out.closeEntry()
        }
    }.toByteArray()

    private class TestModel(override val downloadUrl: String) : DownloadableModel {
        override val displayName = "test"
        override val description = "test"
        override val assetDir = "cancel-retry-test"
        override val modelFiles = listOf("encoder.onnx", "decoder.onnx")
        override val sizeLabel = "50KB"
    }
}