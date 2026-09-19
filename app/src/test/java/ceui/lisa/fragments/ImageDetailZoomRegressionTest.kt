package ceui.lisa.fragments

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.lisa.view.DragDismissLayout
import com.blankj.utilcode.util.Utils
import com.github.panpf.sketch.disposeLoad
import com.github.panpf.zoomimage.SketchZoomImageView
import com.github.panpf.zoomimage.util.OffsetCompat
import kotlinx.coroutines.runBlocking
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
import java.time.Duration

/** Exercises the real still-image fragment with each existing zoom preference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = ImageDetailZoomRegressionTest.TestApplication::class)
class ImageDetailZoomRegressionTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit // No native services or networking are needed to test gestures.
    }

    private lateinit var host: ActivityController<FragmentActivity>
    private lateinit var fragment: FragmentImageDetail
    private lateinit var image: SketchZoomImageView

    @Before
    fun setUp() {
        host = Robolectric.buildActivity(FragmentActivity::class.java)
        host.get().setTheme(R.style.AppTheme)
        Utils.init(host.get().application)
        Shaft.sSettings = Settings()
        host.setup()
    }

    @After
    fun tearDown() { host.pause().stop().destroy() }

    @Test
    fun `default double tap still toggles fit and zoom`() {
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT)
        val fit = image.zoomable.minScaleState.value
        doubleTap()
        assertTrue(image.zoomable.transformState.value.scaleX > fit)
        doubleTap()
        assertEquals(fit, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `three level double tap still visits the middle level before maximum`() {
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL)
        val fit = image.zoomable.minScaleState.value
        doubleTap()
        val middle = image.zoomable.transformState.value.scaleX
        assertTrue(middle > fit)
        doubleTap()
        assertTrue(image.zoomable.transformState.value.scaleX > middle)
        doubleTap()
        assertEquals(fit, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `incremental double tap still uses the configured multiplier`() {
        Shaft.sSettings.customZoomAddScale = 1.8f
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_INCREMENTAL)
        val fit = image.zoomable.minScaleState.value
        doubleTap()
        assertEquals(fit * 1.8f, image.zoomable.transformState.value.scaleX, 0.01f)
        doubleTap()
        assertEquals(fit * 1.8f * 1.8f, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `zoomed still image pans and blocks dismiss until its edge`() {
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT)
        runBlocking { image.zoomable.scale(image.zoomable.minScaleState.value * 4f, animated = false) }
        assertFalse(fragment.canSwipeToDismiss(DragDismissLayout.Direction.DOWN))
        assertFalse(fragment.canSwipeToDismiss(DragDismissLayout.Direction.UP))
        val offset = image.zoomable.userTransformState.value.offset
        val start = SystemClock.uptimeMillis()
        event(start, 0, MotionEvent.ACTION_DOWN, 200f, 250f)
        event(start, 16, MotionEvent.ACTION_MOVE, 198f, 249f)
        event(start, 32, MotionEvent.ACTION_MOVE, 165f, 225f)
        event(start, 48, MotionEvent.ACTION_MOVE, 145f, 210f)
        event(start, 64, MotionEvent.ACTION_CANCEL, 145f, 210f)
        assertNotEquals(offset, image.zoomable.userTransformState.value.offset)
        runBlocking { image.zoomable.offsetBy(OffsetCompat(0f, 10000f), animated = false) }
        assertTrue(fragment.canSwipeToDismiss(DragDismissLayout.Direction.DOWN))
    }

    @Test
    fun `tall still images retain read mode and same-aspect replacements retain user zoom`() {
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT, imageWidth = 400, imageHeight = 4000)
        assertNotNull(image.zoomable.readModeState.value)
        assertTrue(image.zoomable.transformState.value.scaleX > image.zoomable.minScaleState.value)
        runBlocking { image.zoomable.scale(image.zoomable.transformState.value.scaleX * 1.5f, animated = false) }
        val before = image.zoomable.userTransformState.value
        image.setImageBitmap(bitmap(800, 8000))
        idle()
        val after = image.zoomable.userTransformState.value
        assertEquals(before.scaleX, after.scaleX, 0.01f)
        assertEquals(before.offset.x, after.offset.x, 0.01f)
        assertEquals(before.offset.y, after.offset.y, 0.01f)
    }

    @Test
    fun `pinch remains available in every still image double tap mode`() {
        for (mode in listOf(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT,
            Settings.DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL, Settings.DOUBLE_TAP_ZOOM_MODE_INCREMENTAL)) {
            attach(mode)
            val fit = image.zoomable.minScaleState.value
            val start = SystemClock.uptimeMillis()
            event(start, 0, MotionEvent.ACTION_DOWN, 150f, 300f)
            multiEvent(start, 16, MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 150f, 250f)
            multiEvent(start, 32, MotionEvent.ACTION_MOVE, 140f, 260f)
            multiEvent(start, 48, MotionEvent.ACTION_MOVE, 100f, 300f)
            multiEvent(start, 64, MotionEvent.ACTION_MOVE, 60f, 340f)
            multiEvent(start, 80, MotionEvent.ACTION_POINTER_UP or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 60f, 340f)
            event(start, 96, MotionEvent.ACTION_UP, 60f, 300f)
            idle(400)
            assertTrue("mode=$mode", image.zoomable.transformState.value.scaleX > fit)
            host.get().supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        }
    }

    private fun multiEvent(start: Long, delay: Long, action: Int, firstX: Float, secondX: Float) {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = arrayOf(firstX, secondX).map { coordinate -> MotionEvent.PointerCoords().apply {
            x = coordinate
            y = 300f
            pressure = 1f
            size = 1f
        } }.toTypedArray()
        MotionEvent.obtain(start, start + delay, action, 2, properties, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
            image.dispatchTouchEvent(it)
            it.recycle()
        }
        idle()
    }

    private fun attach(mode: Int, imageWidth: Int = 800, imageHeight: Int = 400) {
        Shaft.sSettings.doubleTapZoomMode = mode
        val container = FrameLayout(host.get()).apply { id = View.generateViewId() }
        host.get().setContentView(container)
        fragment = FragmentImageDetail.newInstance("content://zoom-test/still")
        host.get().supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
        image = fragment.requireView().findViewById(R.id.image)
        image.disposeLoad()
        assertEquals(View.VISIBLE, image.visibility)
        image.layoutParams = android.widget.RelativeLayout.LayoutParams(400, 600)
        image.setImageBitmap(bitmap(imageWidth, imageHeight))
        image.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        image.layout(0, 0, 400, 600)
        idle()
    }

    private fun bitmap(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
    }

    private fun doubleTap() {
        var start = SystemClock.uptimeMillis()
        event(start, 0, MotionEvent.ACTION_DOWN, 200f, 300f)
        event(start, 16, MotionEvent.ACTION_UP, 200f, 300f)
        idle(80)
        start = SystemClock.uptimeMillis()
        event(start, 0, MotionEvent.ACTION_DOWN, 200f, 300f)
        event(start, 16, MotionEvent.ACTION_UP, 200f, 300f)
        idle(600)
    }

    private fun event(start: Long, delay: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(start, start + delay, action, x, y, 0).also {
            image.dispatchTouchEvent(it)
            it.recycle()
        }
        idle()
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
