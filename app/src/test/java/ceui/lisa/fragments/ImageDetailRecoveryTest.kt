package ceui.lisa.fragments

import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.pixiv.download.repairablePngBytes
import ceui.pixiv.imageloader.ImageFetcher
import ceui.pixiv.imageloader.ImageLoadTask
import ceui.pixiv.imageloader.ImageRequest
import ceui.pixiv.imageloader.ImageTaskRegistry
import com.blankj.utilcode.util.Utils
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.request.ImageData
import com.github.panpf.sketch.request.RequestInterceptor
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.SingletonSketch
import com.github.panpf.sketch.request.ImageRequest as SketchImageRequest
import com.github.panpf.zoomimage.SketchZoomImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ImageDetailRecoveryTest.TestApplication::class)
class ImageDetailRecoveryTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    private lateinit var host: ActivityController<FragmentActivity>
    private lateinit var fragment: FragmentImageDetail
    private lateinit var image: SketchZoomImageView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val files = mutableListOf<File>()
    private val urls = mutableListOf<String>()
    private val fetches = AtomicInteger()
    private val allowFetch = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        host = Robolectric.buildActivity(FragmentActivity::class.java)
        host.get().setTheme(R.style.AppTheme)
        Utils.init(host.get().application)
        Shaft.sSettings = Settings()
        host.setup()
        val container = FrameLayout(host.get()).apply { id = View.generateViewId() }
        host.get().setContentView(container)
        fragment = FragmentImageDetail.newInstance("content://recovery/placeholder")
        host.get().supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
        image = fragment.requireView().findViewById(R.id.image)
    }

    @After
    fun tearDown() {
        scope.cancel()
        urls.forEach(ImageTaskRegistry::remove)
        host.pause().stop().destroy()
        files.forEach { it.delete() }
    }

    @Test
    fun `persistent decode errors redownload at most once then expose manual retry`() {
        val file = seed("https://example.test/persistent.jpg")
        val firstRequest = currentRequest()
        failCurrentRequest()
        await { fetches.get() == 1 }
        shadowOf(Looper.getMainLooper()).idle()
        allowFetch.complete(Unit)
        // Deliver the next decoder failure only after the new Success has rendered a request.
        await { currentRequest() !== firstRequest }
        failCurrentRequest()
        await { errorVisible() || fetches.get() > 1 }

        assertEquals(1, fetches.get())
        assertTrue(file.exists())
        assertTrue(errorVisible())
    }

    @Test
    fun `snapshot decode errors preserve the archived file without retrying its task`() {
        val file = seed("shaftsnap://recovery/pages/0.jpg")
        val original = file.readBytes()
        failCurrentRequest()
        await { errorVisible() || !file.exists() || fetches.get() > 0 }

        assertTrue(file.exists())
        assertArrayEquals(original, file.readBytes())
        assertEquals(0, fetches.get())
    }

    @Test
    fun `late local decoder error after destroying the view is ignored`() {
        ReflectionHelpers.callInstanceMethod<Void>(fragment, "loadFromLocal",
            ClassParameter.from(Uri::class.java, Uri.parse("content://recovery/local")),
            ClassParameter.from(String::class.java, "https://example.test/local.png"),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        val request = requireNotNull(currentRequest())
        host.get().supportFragmentManager.beginTransaction().remove(fragment).commitNow()

        request.listener!!.onError(request, ImageResult.Error(request, null, IOException("late decode error")))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `another decoder error after successful ECC repair still offers manual retry`() {
        val requests = AtomicInteger()
        // Fail at the image-loader boundary; no native decoder is needed for this recovery test.
        val sketch = Sketch.Builder(host.get()).components {
            addRequestInterceptor(object : RequestInterceptor {
                override val key = "decode-failure"
                override val sortWeight = 0
                override fun equals(other: Any?) = this === other
                override fun hashCode() = System.identityHashCode(this)
                override fun toString() = key
                override suspend fun intercept(chain: RequestInterceptor.Chain): Result<ImageData> {
                    requests.incrementAndGet()
                    return Result.failure(IOException("decode error"))
                }
            })
        }.build()
        SingletonSketch.setUnsafe(sketch)
        image.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        image.layout(0, 0, 400, 600)
        try {
            val (healthy, damaged) = repairablePngBytes()
            val file = seed("https://example.test/healable.png", damaged)
            await { errorVisible() }

            assertArrayEquals(healthy, file.readBytes())
            assertEquals(2, requests.get())
            assertEquals(0, fetches.get())
        } finally {
            SingletonSketch.reset()
            sketch.shutdown()
        }
    }

    private fun seed(url: String, payload: ByteArray = ByteArray(200).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xD8.toByte()
            this[2] = 0xFF.toByte()
            this[198] = 0xFF.toByte()
            this[199] = 0xD9.toByte()
        }): File {
        val file = File.createTempFile("recovery", ".jpg", host.get().cacheDir).also {
            it.writeBytes(payload)
            files += it
        }
        val task = ImageLoadTask(ImageRequest(url), scope, object : ImageFetcher {
            override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
                fetches.incrementAndGet()
                allowFetch.await()
                file.writeBytes(payload)
                file
            }
        }, initialFile = file, shouldRetryStall = { false })
        val tasks = ReflectionHelpers.getField<MutableMap<String, ImageLoadTask>>(ImageTaskRegistry, "taskMap")
        tasks[url] = task
        urls += url
        ReflectionHelpers.callInstanceMethod<Void>(fragment, "loadFromNetwork",
            ClassParameter.from(String::class.java, url),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        shadowOf(Looper.getMainLooper()).idle()
        return file
    }

    private fun failCurrentRequest() {
        val request = requireNotNull(currentRequest())
        request.listener!!.onError(request, ImageResult.Error(request, null, IOException("decode error")))
    }

    // Capture the real request's listener so late decoder errors can be delivered deterministically.
    private fun currentRequest(): SketchImageRequest? {
        val manager = ReflectionHelpers.callStaticMethod<Any>(
            Class.forName("com.github.panpf.sketch.request.internal.ViewRequestManagerKt"),
            "getRequestManager", ClassParameter.from(View::class.java, image))
        return ReflectionHelpers.callInstanceMethod(manager, "getRequest")
    }

    private fun errorVisible() =
        fragment.requireView().findViewById<View>(R.id.empty_frame).visibility == View.VISIBLE

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Thread.sleep(5)
        }
        assertTrue("recovery did not settle", condition())
    }
}
