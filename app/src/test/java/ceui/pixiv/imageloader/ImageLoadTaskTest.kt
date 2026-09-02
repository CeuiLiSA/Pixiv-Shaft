package ceui.pixiv.imageloader

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageLoadTaskTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `registry reuses the same task for the same url`() {
        val url = "https://i.pximg.net/img-original/img/test-registry.jpg"
        ImageTaskRegistry.remove(url)
        try {
            val first = ImageLoaderV3.obtain(url, autoStart = false)
            val second = ImageLoaderV3.obtain(url, autoStart = false)

            assertSame(first, second)
        } finally {
            ImageTaskRegistry.remove(url)
        }
    }

    @Test
    fun `start is idempotent while fetch is running`() = runTest {
        val release = CompletableDeferred<File>()
        var calls = 0
        val task = ImageLoadTask(
            request = ImageRequest("https://i.pximg.net/img-original/img/running.jpg"),
            scope = this,
            fetcher = object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
                    calls += 1
                    onProgress(12)
                    return release.await()
                }
            },
            elapsedRealtime = { 0L },
        )

        task.start()
        task.start()
        runCurrent()

        assertEquals(1, calls)
        assertEquals(ImageLoadState.Loading(12), task.state.value)

        val file = tempImageFile("running")
        release.complete(file)
        advanceUntilIdle()

        assertEquals(ImageLoadState.Success(file), task.state.value)
    }

    @Test
    fun `retry is required after error and then fetches again`() = runTest {
        val success = tempImageFile("retry")
        var calls = 0
        val task = ImageLoadTask(
            request = ImageRequest("https://i.pximg.net/img-original/img/retry.jpg"),
            scope = this,
            fetcher = object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
                    calls += 1
                    if (calls == 1) throw IllegalStateException("boom")
                    return success
                }
            },
            elapsedRealtime = { 0L },
        )

        task.start()
        advanceUntilIdle()
        assertTrue(task.state.value is ImageLoadState.Error)

        task.start()
        advanceUntilIdle()
        assertEquals("plain start must not retry errors", 1, calls)

        task.retry()
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals(ImageLoadState.Success(success), task.state.value)
    }

    @Test
    fun `awaitFile ignores stale success whose file was removed`() = runTest {
        val firstFile = tempImageFile("stale-first")
        val secondFile = tempImageFile("stale-second")
        val files = ArrayDeque(listOf(firstFile, secondFile))
        var calls = 0
        val task = ImageLoadTask(
            request = ImageRequest("https://i.pximg.net/img-original/img/stale.jpg"),
            scope = this,
            fetcher = object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
                    calls += 1
                    return files.removeFirst()
                }
            },
            elapsedRealtime = { 0L },
        )

        assertEquals(firstFile, task.awaitFile())
        assertTrue(firstFile.delete())
        assertNull(task.currentFile)

        assertEquals(secondFile, task.awaitFile())
        assertEquals(2, calls)
        assertEquals(ImageLoadState.Success(secondFile), task.state.value)
    }

    @Test
    fun `empty fetched file becomes error instead of hanging awaitFile`() = runTest {
        val empty = File.createTempFile("imageloadtask-empty-", ".jpg").apply { deleteOnExit() }
        val task = ImageLoadTask(
            request = ImageRequest("https://i.pximg.net/img-original/img/empty.jpg"),
            scope = this,
            fetcher = object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File = empty
            },
            elapsedRealtime = { 0L },
        )

        task.start()
        advanceUntilIdle()
        assertTrue(task.state.value is ImageLoadState.Error)
        assertNull(task.currentFile)

        val thrown = runCatching { task.awaitFile() }.exceptionOrNull()
        assertTrue(thrown is IOException)
    }

    @Test
    fun `late progress cannot overwrite success`() = runTest {
        val file = tempImageFile("late-progress")
        lateinit var progress: (Int) -> Unit
        val task = ImageLoadTask(
            request = ImageRequest("https://i.pximg.net/img-original/img/late-progress.jpg"),
            scope = this,
            fetcher = object : ImageFetcher {
                override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
                    progress = onProgress
                    onProgress(30)
                    return file
                }
            },
            elapsedRealtime = { 0L },
        )

        task.start()
        advanceUntilIdle()
        assertEquals(ImageLoadState.Success(file), task.state.value)

        progress(80)

        assertEquals(ImageLoadState.Success(file), task.state.value)
    }

    private fun tempImageFile(name: String): File {
        return File.createTempFile("imageloadtask-$name-", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
    }
}
