package ceui.pixiv.ui.common

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import ceui.lisa.activities.Shaft
import com.github.panpf.zoomimage.SketchZoomImageView
import com.github.panpf.zoomimage.util.OffsetCompat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ZoomSyncTest.TestApplication::class)
class ZoomSyncTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @Test
    fun enhancedImageKeepsOriginalAlignedBeyondItsDefaultZoomLimit() {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val original = SketchZoomImageView(host.get())
            val enhanced = SketchZoomImageView(host.get())
            host.get().setContentView(FrameLayout(host.get()).apply {
                addView(original, FrameLayout.LayoutParams(100, 100))
                addView(enhanced, FrameLayout.LayoutParams(100, 100))
            })
            original.setImageBitmap(bitmap(400))
            enhanced.setImageBitmap(bitmap(800))
            val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
            listOf(original, enhanced).forEach {
                it.measure(exact, exact)
                it.layout(0, 0, 100, 100)
            }
            shadowOf(Looper.getMainLooper()).idle()

            val originalDefaultMax = original.zoomable.maxScaleState.value /
                    original.zoomable.baseTransformState.value.scaleX
            val enhancedMax = enhanced.zoomable.maxScaleState.value /
                    enhanced.zoomable.baseTransformState.value.scaleX
            assertTrue(enhancedMax > originalDefaultMax)

            setupZoomSync(host.get(), original, enhanced)
            shadowOf(Looper.getMainLooper()).idle()
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                MotionEvent.obtain(now, now + 16, action, 50f, 50f, 0).also {
                    enhanced.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            val zoom = (originalDefaultMax + enhancedMax) / 2f
            runBlocking {
                enhanced.zoomable.scale(
                    enhanced.zoomable.baseTransformState.value.scaleX * zoom,
                    animated = false,
                )
                enhanced.zoomable.offsetBy(OffsetCompat(-25f, -20f), animated = false)
            }
            shadowOf(Looper.getMainLooper()).idle()

            val originalTransform = original.zoomable.userTransformState.value
            val enhancedTransform = enhanced.zoomable.userTransformState.value
            assertEquals(zoom, originalTransform.scaleX, 0.01f)
            assertEquals(enhancedTransform.scaleX, originalTransform.scaleX, 0.01f)
            assertEquals(enhancedTransform.offset.x, originalTransform.offset.x, 1f)
            assertEquals(enhancedTransform.offset.y, originalTransform.offset.y, 1f)

            for (active in listOf(enhanced, original)) {
                val follower = if (active === enhanced) original else enhanced
                val start = SystemClock.uptimeMillis()
                touch(active, start, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
                touch(active, start, 16, MotionEvent.ACTION_UP, floatArrayOf(40f))
                val maxScale = active.zoomable.maxScaleState.value
                runBlocking { active.zoomable.scale(maxScale * 0.95f, animated = false) }
                shadowOf(Looper.getMainLooper()).idle()

                val pinchStart = SystemClock.uptimeMillis()
                touch(active, pinchStart, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
                touch(
                    active, pinchStart, 16,
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    floatArrayOf(40f, 60f)
                )
                touch(active, pinchStart, 32, MotionEvent.ACTION_MOVE, floatArrayOf(15f, 85f), 40f)
                touch(active, pinchStart, 48, MotionEvent.ACTION_MOVE, floatArrayOf(5f, 95f), 35f)
                shadowOf(Looper.getMainLooper()).idle()

                val activeTransform = active.zoomable.userTransformState.value
                val followerTransform = follower.zoomable.userTransformState.value
                assertTrue(
                    "pinch should keep rubber-band zoom",
                    active.zoomable.transformState.value.scaleX > maxScale
                )
                assertEquals(activeTransform.scaleX, followerTransform.scaleX, 0.05f)
                assertEquals(activeTransform.offset.x, followerTransform.offset.x, 1f)
                assertEquals(activeTransform.offset.y, followerTransform.offset.y, 1f)

                touch(
                    active, pinchStart, 64,
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    floatArrayOf(5f, 95f), 35f
                )
                touch(active, pinchStart, 80, MotionEvent.ACTION_UP, floatArrayOf(5f), 35f)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
                val settled = active.zoomable.userTransformState.value
                val followerSettled = follower.zoomable.userTransformState.value
                assertEquals(maxScale, active.zoomable.transformState.value.scaleX, 0.01f)
                assertEquals(settled.scaleX, followerSettled.scaleX, 0.05f)
                assertEquals(settled.offset.x, followerSettled.offset.x, 1f)
                assertEquals(settled.offset.y, followerSettled.offset.y, 1f)
            }
        } finally {
            host.pause().stop().destroy()
        }
    }

    private fun touch(
        image: SketchZoomImageView,
        start: Long,
        delay: Long,
        action: Int,
        xs: FloatArray,
        y: Float = 50f,
    ) {
        val properties = Array(xs.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(xs.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = xs[index]
                this.y = y
                pressure = 1f
                size = 1f
            }
        }
        MotionEvent.obtain(
            start, start + delay, action, xs.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        ).also {
            image.dispatchTouchEvent(it)
            it.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun bitmap(size: Int) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
    }
}