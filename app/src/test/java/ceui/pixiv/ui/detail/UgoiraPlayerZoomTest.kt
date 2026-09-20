package ceui.pixiv.ui.detail

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import com.blankj.utilcode.util.Utils
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.github.panpf.zoomimage.util.OffsetCompat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = UgoiraPlayerZoomTest.TestApplication::class)
class UgoiraPlayerZoomTest {
    class TestApplication : Shaft() {
        private val client by lazy { OkHttpClient() }
        override fun onCreate() = Unit
        override fun getOkHttpClient() = client
    }

    private lateinit var host: Activity
    private lateinit var parent: InterceptParent
    private lateinit var player: UgoiraPlayerView
    private val illust = Illust(id = 1122, type = "ugoira", width = 800, height = 400, page_count = 1)
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    }

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        Glide.init(RuntimeEnvironment.getApplication(), GlideBuilder())
        Shaft.sSettings = Settings().apply { isAutoPlayUgoira = false }
        host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(host, R.style.AppTheme)
        parent = InterceptParent(context)
        player = UgoiraPlayerView(context)
        parent.addView(player)
        host.setContentView(parent)
        player.bind(owner, illust, maxHeight = 600)
        showFrame()
        layout(400, 600)
    }

    @After
    fun tearDown() {
        player.recycle()
        host.finish()
        Glide.tearDown()
    }

    @Test
    fun `pinch scales and drag pans without triggering the single tap action`() {
        // Hardware video covers the bitmap; its TextureView must leave gestures to ZoomImage.
        (0 until player.childCount).map(player::getChildAt).filterIsInstance<TextureView>().single().visibility = View.VISIBLE
        layout(400, 600)
        var clicks = 0
        player.setOnClickListener { clicks++ }
        val start = SystemClock.uptimeMillis()
        event(start, 0, MotionEvent.ACTION_DOWN, 150f to 100f)
        event(start, 16, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 150f to 100f, 250f to 100f)
        event(start, 32, MotionEvent.ACTION_MOVE, 140f to 100f, 260f to 100f)
        event(start, 48, MotionEvent.ACTION_MOVE, 100f to 100f, 300f to 100f)
        event(start, 64, MotionEvent.ACTION_MOVE, 60f to 100f, 340f to 100f)
        event(start, 80, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 60f to 100f, 340f to 100f)
        event(start, 96, MotionEvent.ACTION_UP, 60f to 100f)
        idle(400)
        assertTrue(player.imageView.zoomable.userTransformState.value.scaleX > 1f)
        val beforePan = player.imageView.zoomable.userTransformState.value.offset
        drag()
        assertNotEquals(beforePan, player.imageView.zoomable.userTransformState.value.offset)
        assertEquals(0, clicks)
    }

    @Test
    fun `double tap switches between fit and zoom without opening the viewer`() {
        var clicks = 0
        player.setOnClickListener { clicks++ }
        doubleTap()
        assertTrue(player.imageView.zoomable.userTransformState.value.scaleX > 1f)
        doubleTap()
        assertEquals(1f, player.imageView.zoomable.userTransformState.value.scaleX, 0.01f)
        assertEquals(0, clicks)
        tap()
        idle(400)
        assertEquals(1, clicks)
    }

    @Test
    fun `successive playback frames retain scale and pan and same work rebind retains zoom`() {
        zoomAndPan()
        val before = player.imageView.zoomable.userTransformState.value
        repeat(10) { showFrame() }
        assertEquals(before, player.imageView.zoomable.userTransformState.value)
        player.bind(owner, illust.copy(is_bookmarked = true), maxHeight = 600)
        assertEquals(before, player.imageView.zoomable.userTransformState.value)
        player.bind(owner, illust.copy(id = 1123), maxHeight = 600)
        showFrame()
        assertEquals(1f, player.imageView.zoomable.userTransformState.value.scaleX, 0.001f)
    }

    @Test
    fun `video and bitmap map to the same visible rectangle after zoom pan and resize`() {
        val texture = (0 until player.childCount).map(player::getChildAt).filterIsInstance<TextureView>().single()
        texture.visibility = View.VISIBLE
        ReflectionHelpers.setField(player, "videoW", 1600)
        ReflectionHelpers.setField(player, "videoH", 800)
        for ((width, height) in listOf(400 to 600, 320 to 700, 800 to 400)) {
            player.bind(owner, illust, maxHeight = 0, fitToViewport = true)
            layout(width, height)
            zoomAndPan()
            val videoRect = RectF(0f, 0f, texture.width.toFloat(), texture.height.toFloat())
            texture.getTransform(Matrix()).mapRect(videoRect)
            val drawable = requireNotNull(player.imageView.drawable)
            val imageRect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
            player.imageView.imageMatrix.mapRect(imageRect)
            assertEquals(imageRect.left, videoRect.left, 0.1f)
            assertEquals(imageRect.top, videoRect.top, 0.1f)
            assertEquals(imageRect.right, videoRect.right, 0.1f)
            assertEquals(imageRect.bottom, videoRect.bottom, 0.1f)
            assertEquals(width, texture.width)
            assertEquals(height, texture.height)
        }
    }

    @Test
    fun `fit gesture yields to parent while zoomed pan keeps the gesture`() {
        parent.interceptRequests.clear()
        drag()
        assertTrue(parent.interceptRequests.contains(false))
        zoomAndPan()
        parent.interceptRequests.clear()
        drag()
        assertTrue(parent.interceptRequests.contains(true))
        assertFalse(parent.interceptRequests.contains(false))
    }

    @Test
    fun `inline height uses available width and recycled views discard pending single taps`() {
        layout(320, 600)
        assertEquals(160, player.imageView.height)
        var clicks = 0
        player.setOnClickListener { clicks++ }
        tap()
        player.recycle()
        idle(400)
        assertEquals(0, clicks)
    }

    @Test
    fun `video can zoom before a network preview has loaded`() {
        player.bind(owner, illust.copy(id = 1123), maxHeight = 600)
        assertEquals(800, player.imageView.zoomable.contentSizeState.value.width)
        doubleTap()
        assertTrue(player.imageView.zoomable.userTransformState.value.scaleX > 1f)
    }

    @Test
    fun `failed preview retries on rebind while successful preview is retained`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(
                Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=")
            )))
            val work = illust.copy(id = 1123, image_urls = ImageUrls(large = server.url("/preview.png").toString()))
            val target = DrawableImageViewTarget(player.imageView)
            player.bind(owner, work, maxHeight = 600)
            layout(400, 600)
            awaitUi { target.request?.isRunning == false }
            assertEquals(1, server.requestCount)
            assertFalse(requireNotNull(target.request).isComplete)
            // A failed download must still leave a valid canvas for hardware video gestures.
            assertEquals(800, player.imageView.zoomable.contentSizeState.value.width)
            doubleTap()
            assertTrue(player.imageView.zoomable.userTransformState.value.scaleX > 1f)

            player.bind(owner, work, maxHeight = 600)
            awaitUi { target.request?.isComplete == true }
            assertEquals(2, server.requestCount)
            val completed = target.request
            player.bind(owner, work, maxHeight = 600)
            assertSame(completed, target.request)
            assertEquals(2, server.requestCount)
        }
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            idle()
            Thread.sleep(10)
        }
        assertTrue("Glide request did not reach the expected state", condition())
    }

    private fun showFrame() {
        player.imageView.setImageBitmap(Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
        })
        idle()
    }

    private fun layout(width: Int, height: Int) {
        player.layoutParams = FrameLayout.LayoutParams(width, height)
        player.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        player.layout(0, 0, width, height)
        idle()
    }

    private fun zoomAndPan() {
        val zoom = player.imageView.zoomable
        runBlocking {
            zoom.scale(zoom.minScaleState.value * 3f, animated = false)
            zoom.offsetBy(OffsetCompat(-30f, -15f), animated = false)
        }
        idle()
    }

    private fun doubleTap() {
        tap()
        idle(80)
        tap()
        idle(600)
    }

    private fun tap() {
        val start = SystemClock.uptimeMillis()
        event(start, 0, MotionEvent.ACTION_DOWN, 200f to 100f)
        event(start, 16, MotionEvent.ACTION_UP, 200f to 100f)
    }

    private fun drag() {
        val start = SystemClock.uptimeMillis()
        event(start, 0, MotionEvent.ACTION_DOWN, 200f to 100f)
        event(start, 16, MotionEvent.ACTION_MOVE, 198f to 100f)
        event(start, 32, MotionEvent.ACTION_MOVE, 175f to 100f)
        event(start, 48, MotionEvent.ACTION_MOVE, 150f to 100f)
        event(start, 64, MotionEvent.ACTION_CANCEL, 150f to 100f)
        idle()
    }

    private fun event(start: Long, delay: Long, action: Int, vararg points: Pair<Float, Float>) {
        val properties = points.indices.map { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coords = points.map { point -> MotionEvent.PointerCoords().apply {
            x = point.first
            y = point.second
            pressure = 1f
            size = 1f
        } }.toTypedArray()
        MotionEvent.obtain(start, start + delay, action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
            // TextureView is on top during video playback: events must still reach the zoom view.
            player.dispatchTouchEvent(it)
            it.recycle()
        }
        idle()
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private class InterceptParent(context: android.content.Context) : FrameLayout(context) {
        val interceptRequests = mutableListOf<Boolean>()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            interceptRequests += disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }
}
