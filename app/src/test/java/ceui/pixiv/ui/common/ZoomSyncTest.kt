package ceui.pixiv.ui.common

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.activities.Shaft
import com.github.panpf.zoomimage.SketchZoomImageView
import com.github.panpf.zoomimage.util.IntSizeCompat
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.view.zoom.ZoomAnimationSpec
import com.github.panpf.zoomimage.zoom.ContinuousTransformType
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ZoomSyncTest.TestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
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
                val passive = if (active === enhanced) original else enhanced
                val before = passive.zoomable.userTransformState.value.offset
                val activeStart = SystemClock.uptimeMillis()
                touch(active, activeStart, 0, MotionEvent.ACTION_DOWN, floatArrayOf(50f))
                val ignoredStart = SystemClock.uptimeMillis()
                touch(passive, ignoredStart, 0, MotionEvent.ACTION_DOWN, floatArrayOf(50f))
                touch(passive, ignoredStart, 16, MotionEvent.ACTION_MOVE, floatArrayOf(35f), 35f)
                touch(passive, ignoredStart, 32, MotionEvent.ACTION_MOVE, floatArrayOf(20f), 20f)
                assertEquals(before.x, passive.zoomable.userTransformState.value.offset.x, 1f)
                assertEquals(before.y, passive.zoomable.userTransformState.value.offset.y, 1f)

                touch(active, activeStart, 48, MotionEvent.ACTION_UP, floatArrayOf(50f))
                touch(passive, ignoredStart, 48, MotionEvent.ACTION_MOVE, floatArrayOf(10f), 10f)
                assertEquals(before.x, passive.zoomable.userTransformState.value.offset.x, 1f)
                assertEquals(before.y, passive.zoomable.userTransformState.value.offset.y, 1f)
                touch(passive, ignoredStart, 64, MotionEvent.ACTION_UP, floatArrayOf(10f), 10f)
            }

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

    @Test
    fun dragBeforePinchStaysAlignedWhenReturningToOneFinger() {
        withSyncedImages { original, enhanced ->
            for (active in listOf(original, enhanced)) {
                val follower = if (active === original) enhanced else original
                val start = SystemClock.uptimeMillis()
                touch(active, start, 0, MotionEvent.ACTION_DOWN, floatArrayOf(50f))
                touch(active, start, 16, MotionEvent.ACTION_UP, floatArrayOf(50f))
                runBlocking {
                    active.zoomable.scale(active.zoomable.baseTransformState.value.scaleX * 4f)
                }
                shadowOf(Looper.getMainLooper()).idle()

                val dragStart = SystemClock.uptimeMillis() + 500
                touch(active, dragStart, 0, MotionEvent.ACTION_DOWN, floatArrayOf(50f))
                touch(active, dragStart, 16, MotionEvent.ACTION_MOVE, floatArrayOf(45f))
                touch(active, dragStart, 32, MotionEvent.ACTION_MOVE, floatArrayOf(25f))
                touch(
                    active, dragStart, 48,
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    floatArrayOf(25f, 65f),
                )
                touch(active, dragStart, 64, MotionEvent.ACTION_MOVE, floatArrayOf(15f, 75f))
                touch(
                    active, dragStart, 80,
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    floatArrayOf(15f, 75f),
                )
                touch(active, dragStart, 96, MotionEvent.ACTION_MOVE, floatArrayOf(10f))

                val activeTransform = active.zoomable.userTransformState.value
                val followerTransform = follower.zoomable.userTransformState.value
                assertEquals(activeTransform.scaleX, followerTransform.scaleX, 0.01f)
                assertEquals(activeTransform.offset.x, followerTransform.offset.x, 1f)
                assertEquals(activeTransform.offset.y, followerTransform.offset.y, 1f)
                touch(active, dragStart, 112, MotionEvent.ACTION_UP, floatArrayOf(10f))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            }
        }
    }

    @Test
    fun pinchStaysAlignedWhenOriginalSizeArrivesAfterSetup() {
        withSyncedImages { original, enhanced ->
            enhanced.zoomable.setContentOriginSize(IntSizeCompat(1600, 1600))
            shadowOf(Looper.getMainLooper()).idle()

            val start = SystemClock.uptimeMillis()
            touch(original, start, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
            touch(original, start, 16, MotionEvent.ACTION_UP, floatArrayOf(40f))
            val maxScale = original.zoomable.maxScaleState.value
            runBlocking { original.zoomable.scale(maxScale * 0.95f) }
            shadowOf(Looper.getMainLooper()).idle()

            val pinchStart = SystemClock.uptimeMillis() + 500
            touch(original, pinchStart, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
            touch(
                original, pinchStart, 16,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                floatArrayOf(40f, 60f),
            )
            touch(original, pinchStart, 32, MotionEvent.ACTION_MOVE, floatArrayOf(15f, 85f))
            touch(original, pinchStart, 48, MotionEvent.ACTION_MOVE, floatArrayOf(5f, 95f))

            val originalTransform = original.zoomable.userTransformState.value
            val enhancedTransform = enhanced.zoomable.userTransformState.value
            assertEquals(originalTransform.scaleX, enhancedTransform.scaleX, 0.05f)
            assertEquals(originalTransform.offset.x, enhancedTransform.offset.x, 1f)
            assertEquals(originalTransform.offset.y, enhancedTransform.offset.y, 1f)
            touch(original, pinchStart, 64, MotionEvent.ACTION_CANCEL, floatArrayOf(5f, 95f))
        }
    }

    @Test
    fun originalSizeUpdatePreservesAnActivePinch() {
        withSyncedImages { original, enhanced ->
            val start = SystemClock.uptimeMillis()
            touch(original, start, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
            touch(original, start, 16, MotionEvent.ACTION_UP, floatArrayOf(40f))
            runBlocking {
                original.zoomable.scale(original.zoomable.baseTransformState.value.scaleX * 4f)
            }
            shadowOf(Looper.getMainLooper()).idle()

            val pinchStart = SystemClock.uptimeMillis() + 500
            touch(original, pinchStart, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
            touch(
                original, pinchStart, 16,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                floatArrayOf(40f, 60f),
            )
            touch(original, pinchStart, 32, MotionEvent.ACTION_MOVE, floatArrayOf(30f, 70f))
            val before = original.zoomable.userTransformState.value

            enhanced.zoomable.setContentOriginSize(IntSizeCompat(1600, 1600))
            shadowOf(Looper.getMainLooper()).idle()

            val after = original.zoomable.userTransformState.value
            assertEquals(before.scaleX, after.scaleX, 0.01f)
            assertEquals(before.offset.x, after.offset.x, 1f)
            assertEquals(before.offset.y, after.offset.y, 1f)
            assertEquals(after.scaleX, enhanced.zoomable.userTransformState.value.scaleX, 0.01f)
            assertEquals(after.offset.x, enhanced.zoomable.userTransformState.value.offset.x, 1f)
            assertEquals(after.offset.y, enhanced.zoomable.userTransformState.value.offset.y, 1f)
            touch(original, pinchStart, 48, MotionEvent.ACTION_CANCEL, floatArrayOf(30f, 70f))
        }
    }

    @Test
    fun doubleTapAndSingleFingerDragRemainSynchronized() {
        withSyncedImages { original, enhanced ->
            for (active in listOf(original, enhanced)) {
                val follower = if (active === original) enhanced else original
                val start = SystemClock.uptimeMillis()
                touch(active, start, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
                touch(active, start, 16, MotionEvent.ACTION_UP, floatArrayOf(40f))
                touch(active, start, 100, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
                touch(active, start, 116, MotionEvent.ACTION_UP, floatArrayOf(40f))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

                assertTrue(active.zoomable.userTransformState.value.scaleX > 1f)
                assertEquals(
                    active.zoomable.userTransformState.value.scaleX,
                    follower.zoomable.userTransformState.value.scaleX,
                    0.01f,
                )

                val before = active.zoomable.userTransformState.value.offset
                val dragStart = SystemClock.uptimeMillis()
                touch(active, dragStart, 0, MotionEvent.ACTION_DOWN, floatArrayOf(60f))
                touch(active, dragStart, 16, MotionEvent.ACTION_MOVE, floatArrayOf(50f))
                touch(active, dragStart, 32, MotionEvent.ACTION_MOVE, floatArrayOf(25f))
                val after = active.zoomable.userTransformState.value
                assertTrue(after.offset.x < before.x)
                assertEquals(after.scaleX, follower.zoomable.userTransformState.value.scaleX, 0.01f)
                assertEquals(after.offset.x, follower.zoomable.userTransformState.value.offset.x, 1f)
                assertEquals(after.offset.y, follower.zoomable.userTransformState.value.offset.y, 1f)
                touch(active, dragStart, 48, MotionEvent.ACTION_CANCEL, floatArrayOf(25f))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            }
        }
    }

    @Test
    fun tapsOnDifferentImagesDoNotTriggerDoubleTapZoom() {
        withSyncedImages { original, enhanced ->
            for ((first, second) in listOf(original to enhanced, enhanced to original)) {
                val start = SystemClock.uptimeMillis()
                touch(first, start, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
                touch(first, start, 16, MotionEvent.ACTION_UP, floatArrayOf(40f))
                touch(second, start, 100, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
                touch(second, start, 116, MotionEvent.ACTION_UP, floatArrayOf(40f))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

                assertEquals(1f, original.zoomable.userTransformState.value.scaleX, 0.01f)
                assertEquals(1f, enhanced.zoomable.userTransformState.value.scaleX, 0.01f)
            }
        }
    }

    @Test
    fun originalSizeUpdatePreservesAnOverscrolledPinch() {
        withSyncedImages { original, enhanced ->
            original.zoomable.setAnimationSpec(ZoomAnimationSpec.Default)
            enhanced.zoomable.setAnimationSpec(ZoomAnimationSpec.Default)
            val start = SystemClock.uptimeMillis()
            touch(original, start, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
            touch(original, start, 16, MotionEvent.ACTION_UP, floatArrayOf(40f))
            val previousMaxUserScale = original.zoomable.maxScaleState.value /
                    original.zoomable.baseTransformState.value.scaleX
            val rollbackLimits = mutableListOf<Float>()
            (original.context as FragmentActivity).lifecycleScope.launch {
                original.zoomable.transformState.collect {
                    if (original.zoomable.continuousTransformTypeState.value == ContinuousTransformType.ROLLBACK) {
                        rollbackLimits += original.zoomable.maxScaleState.value /
                                original.zoomable.baseTransformState.value.scaleX
                    }
                }
            }
            runBlocking { original.zoomable.scale(original.zoomable.maxScaleState.value * 0.95f) }
            shadowOf(Looper.getMainLooper()).idle()

            val pinchStart = SystemClock.uptimeMillis() + 500
            touch(original, pinchStart, 0, MotionEvent.ACTION_DOWN, floatArrayOf(40f))
            touch(
                original, pinchStart, 16,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                floatArrayOf(40f, 60f),
            )
            touch(original, pinchStart, 32, MotionEvent.ACTION_MOVE, floatArrayOf(15f, 85f))
            touch(original, pinchStart, 48, MotionEvent.ACTION_MOVE, floatArrayOf(5f, 95f))
            val before = original.zoomable.userTransformState.value
            assertTrue(original.zoomable.transformState.value.scaleX > original.zoomable.maxScaleState.value)

            enhanced.zoomable.setContentOriginSize(IntSizeCompat(810, 810))
            shadowOf(Looper.getMainLooper()).idle()

            val after = original.zoomable.userTransformState.value
            assertEquals(before.scaleX, after.scaleX, 0.01f)
            assertEquals(before.offset.x, after.offset.x, 1f)
            assertEquals(before.offset.y, after.offset.y, 1f)
            assertEquals(after.scaleX, enhanced.zoomable.userTransformState.value.scaleX, 0.01f)

            touch(original, pinchStart, 64, MotionEvent.ACTION_MOVE, floatArrayOf(0f, 100f))
            val continued = original.zoomable.userTransformState.value
            assertEquals(continued.scaleX, enhanced.zoomable.userTransformState.value.scaleX, 0.01f)
            assertEquals(continued.offset.x, enhanced.zoomable.userTransformState.value.offset.x, 1f)
            assertEquals(continued.offset.y, enhanced.zoomable.userTransformState.value.offset.y, 1f)
            touch(original, pinchStart, 80, MotionEvent.ACTION_CANCEL, floatArrayOf(0f, 100f))

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertTrue("The rebound must be observed", rollbackLimits.isNotEmpty())
            assertTrue(rollbackLimits.all { kotlin.math.abs(it - previousMaxUserScale) < 0.01f })
            val nextMaxUserScale = original.zoomable.maxScaleState.value /
                    original.zoomable.baseTransformState.value.scaleX
            assertTrue(nextMaxUserScale > previousMaxUserScale)
            assertEquals(
                nextMaxUserScale,
                enhanced.zoomable.maxScaleState.value / enhanced.zoomable.baseTransformState.value.scaleX,
                0.01f,
            )
            val settled = original.zoomable.userTransformState.value
            assertEquals(settled.scaleX, enhanced.zoomable.userTransformState.value.scaleX, 0.01f)
            assertEquals(settled.offset.x, enhanced.zoomable.userTransformState.value.offset.x, 1f)
            assertEquals(settled.offset.y, enhanced.zoomable.userTransformState.value.offset.y, 1f)
        }
    }

    private fun withSyncedImages(block: (SketchZoomImageView, SketchZoomImageView) -> Unit) {
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
            setupZoomSync(host.get(), original, enhanced)
            shadowOf(Looper.getMainLooper()).idle()
            block(original, enhanced)
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
