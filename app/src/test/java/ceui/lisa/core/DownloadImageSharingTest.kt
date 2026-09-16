package ceui.lisa.core

import ceui.pixiv.imageloader.ImageFetcher
import ceui.pixiv.imageloader.ImageLoadState
import ceui.pixiv.imageloader.ImageLoadTask
import ceui.pixiv.imageloader.ImageLoaderV3
import ceui.pixiv.imageloader.ImageRequest
import ceui.pixiv.imageloader.ImageTaskRegistry
import ceui.pixiv.progress.ProgressTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadImageSharingTest {
    private val dispatcher = StandardTestDispatcher()
    private val io = Executors.newSingleThreadExecutor()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        io.shutdownNow()
        Dispatchers.resetMain()
    }

    @Test fun `save joins running display fetch and receives its existing progress and file`() = runTest(dispatcher) {
        val result = CompletableDeferred<File>()
        var fetches = 0
        val image = ImageLoadTask(ImageRequest("https://example.test/shared.jpg"), this,
            object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
                    fetches++
                    onProgress(42)
                    return result.await()
                }
            }, { 0L })
        image.start()
        runCurrent()

        val joined = CountDownLatch(1)
        val progress = AtomicInteger(-1)
        val saved = AtomicReference<String>()
        val failed = AtomicReference<Throwable>()
        DownloadTask.launch(io, { emitter ->
            val file = ImageLoaderV3.awaitExistingFile(image) {
                progress.set(it)
                joined.countDown()
            }
            emitter.onNext(file.path)
            emitter.onComplete()
        }, { saved.set(it) }, { failed.set(it) }, {})
        assertTrue(joined.await(2, TimeUnit.SECONDS))
        assertEquals(42, progress.get())
        assertEquals(1, fetches)

        val file = tempImage()
        result.complete(file)
        runCurrent()
        io.submit {}.get(2, TimeUnit.SECONDS)
        assertEquals(file.path, saved.get())
        assertEquals(ImageLoadState.Success(file), image.state.value)
        assertEquals(1, fetches)
        assertNull(failed.get())
    }

    @Test fun `pausing save interrupts only the waiter and display still completes`() = runTest(dispatcher) {
        val result = CompletableDeferred<File>()
        val image = ImageLoadTask(ImageRequest("https://example.test/cancel.jpg"), this,
            object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
                    onProgress(31)
                    return result.await()
                }
            }, { 0L })
        image.start()
        runCurrent()
        val joined = CountDownLatch(1)
        val saved = AtomicReference<String>()
        val failed = AtomicReference<Throwable>()
        val save = DownloadTask.launch(io, { emitter ->
            val file = ImageLoaderV3.awaitExistingFile(image) { joined.countDown() }
            emitter.onNext(file.path)
        }, { saved.set(it) }, { failed.set(it) }, {})
        assertTrue(joined.await(2, TimeUnit.SECONDS))
        save.cancel()
        io.submit {}.get(2, TimeUnit.SECONDS)
        assertEquals(ImageLoadState.Loading(31), image.state.value)
        val file = tempImage()
        result.complete(file)
        runCurrent()
        assertEquals(ImageLoadState.Success(file), image.state.value)
        assertNull(saved.get())
        assertNull(failed.get())
    }

    @Test fun `shared fetch failure fails the save instead of hanging or claiming success`() = runTest(dispatcher) {
        val result = CompletableDeferred<File>()
        val image = ImageLoadTask(ImageRequest("https://example.test/fail.jpg"), this,
            object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
                    onProgress(11)
                    return result.await()
                }
            }, { 0L })
        image.start()
        runCurrent()
        val joined = CountDownLatch(1)
        val saved = AtomicReference<String>()
        val failed = AtomicReference<Throwable>()
        DownloadTask.launch(io, { emitter ->
            emitter.onNext(ImageLoaderV3.awaitExistingFile(image) { joined.countDown() }.path)
        }, { saved.set(it) }, { failed.set(it) }, {})
        assertTrue(joined.await(2, TimeUnit.SECONDS))
        val cause = IOException("stream interrupted")
        result.completeExceptionally(cause)
        runCurrent()
        io.submit {}.get(2, TimeUnit.SECONDS)
        // Coroutine stack recovery may copy IOException across the blocking bridge.
        assertTrue(failed.get() is IOException)
        assertEquals(cause.message, failed.get()?.message)
        assertNull(saved.get())
    }

    @Test fun `cache miss does not create or start an image task`() {
        val url = "https://example.test/no-display-task.jpg"
        ImageTaskRegistry.remove(url)
        assertNull(ImageLoaderV3.awaitExistingFile(url) { fail("unexpected progress") })
        assertNull(ImageTaskRegistry.peekTask(url))
    }

    @Test fun `cache evicted before save observes success fails instead of occupying a slot forever`() = runTest(dispatcher) {
        val result = CompletableDeferred<File>()
        val image = ImageLoadTask(ImageRequest("https://example.test/evicted.jpg"), this,
            object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File = result.await()
            }, { 0L })
        val waiterScheduler = TestCoroutineScheduler()
        val waiterScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(waiterScheduler))
        try {
            image.start()
            runCurrent()
            val waiting = waiterScope.async { runCatching { image.awaitFile() } }
            waiterScheduler.runCurrent()
            val file = tempImage()
            result.complete(file)
            runCurrent() // Image task reaches Success; save worker has not consumed it yet.
            assertTrue(file.delete())
            waiterScheduler.runCurrent()
            assertTrue("save must settle when a completed cache file disappears", waiting.isCompleted)
            assertTrue(waiting.await().exceptionOrNull() is IOException)
        } finally {
            waiterScope.cancel()
            waiterScheduler.runCurrent()
        }
    }

    @Test fun `independent download traffic cannot update display progress for the same URL`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("saved image"))
            server.enqueue(MockResponse().setBody("display image"))
            val tracker = ProgressTracker(refreshIntervalMs = 0)
            val guardedCalls = AtomicInteger()
            val guard = Interceptor { chain -> guardedCalls.incrementAndGet(); chain.proceed(chain.request()) }
            val displayClient = tracker.install(OkHttpClient.Builder().addNetworkInterceptor(guard)).build()
            val downloadClient = Manager.buildDownloadOkHttpClient(displayClient, tracker)
            val request = Request.Builder().url(server.url("/same.jpg")).build()
            val displayUpdates = AtomicInteger()
            tracker.track(request.url.toString()) { displayUpdates.incrementAndGet() }.use {
                downloadClient.newCall(request).execute().use { it.body!!.bytes() }
                assertEquals(0, displayUpdates.get())
                displayClient.newCall(request).execute().use { it.body!!.bytes() }
                assertTrue(displayUpdates.get() > 0)
                assertEquals(2, guardedCalls.get())
                assertSame(displayClient.dns, downloadClient.dns)
                assertSame(displayClient.sslSocketFactory, downloadClient.sslSocketFactory)
            }
        }
    }

    private fun tempImage() = File.createTempFile("shared-download-", ".jpg").apply {
        writeBytes(byteArrayOf(1, 2, 3))
        deleteOnExit()
    }
}
