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
import com.blankj.utilcode.util.Utils
import com.github.panpf.sketch.disposeLoad
import com.github.panpf.zoomimage.SketchZoomImageView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 「插画大图长按行为」三态在真实手势路径上的落点。
 *
 * 长按在两种模式下走的是**两套不同的回调**——默认 / 三级是库自带的 `onViewLongPressListener`，增量是自家
 * `GestureDetector.onLongPress`（库的双击缩放被禁用）——所以这里统一用真实 `MotionEvent` 触发，不直接调私有方法，
 * 两条路径都覆盖到。
 *
 * 倍率一律用 `zoomable.scale()` 直接摆到位，不用捏合凑数字：长按落点依赖当前倍率与三个档位的精确关系，
 * 捏合的浮点误差会把用例变成随机的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = ImageDetailLongPressBehaviorTest.TestApplication::class)
class ImageDetailLongPressBehaviorTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
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
    fun tearDown() {
        host.pause().stop().destroy()
    }

    @Test
    fun `none leaves the scale untouched on long press`() {
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT, Settings.LONG_PRESS_BEHAVIOR_NONE)
        val zoomed = scaleTo(image.zoomable.mediumScaleState.value * 1.5f)
        longPress()
        assertEquals(zoomed, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `none still pans after holding past the long press timeout`() {
        // 选「无」时必须**整个不挂** onViewLongPressListener：库的 TouchHelper 一旦发现监听器非 null，
        // 长按一触发就把 longPressExecuted 置 true，「按住超过 500ms 再拖」的平移会被整串吞掉。
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT, Settings.LONG_PRESS_BEHAVIOR_NONE)
        scaleTo(image.zoomable.minScaleState.value * 4f)
        val before = image.zoomable.userTransformState.value.offset
        val start = SystemClock.uptimeMillis()
        event(start, 0, MotionEvent.ACTION_DOWN, 200f, 250f)
        idle(700)
        event(start, 700, MotionEvent.ACTION_MOVE, 165f, 225f)
        event(start, 716, MotionEvent.ACTION_MOVE, 145f, 210f)
        event(start, 732, MotionEvent.ACTION_CANCEL, 145f, 210f)
        idle(600)
        assertNotEquals(before, image.zoomable.userTransformState.value.offset)
    }

    @Test
    fun `reset to minimum always lands on the initial scale`() {
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT, Settings.LONG_PRESS_BEHAVIOR_RESET_MIN)
        val fit = image.zoomable.minScaleState.value
        scaleTo(image.zoomable.maxScaleState.value)
        longPress()
        assertEquals(fit, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `default shrink steps down from above medium to medium`() {
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT, Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL)
        val medium = image.zoomable.mediumScaleState.value
        scaleTo(medium * 1.5f)
        longPress()
        assertEquals(medium, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `default shrink from medium falls back to the initial scale`() {
        // 当前正好停在某一档上时不能把它自己当成「可缩小的一级」，否则就是一次原地动画。
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_DEFAULT, Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL)
        val fit = image.zoomable.minScaleState.value
        scaleTo(image.zoomable.mediumScaleState.value)
        longPress()
        assertEquals(fit, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `three level shrink walks maximum to medium to minimum`() {
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL, Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL)
        val fit = image.zoomable.minScaleState.value
        val medium = image.zoomable.mediumScaleState.value
        scaleTo(image.zoomable.maxScaleState.value)
        longPress()
        assertEquals(medium, image.zoomable.transformState.value.scaleX, 0.01f)
        longPress()
        assertEquals(fit, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `incremental shrink divides by the configured multiplier`() {
        Shaft.sSettings.customZoomAddScale = 1.8f
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_INCREMENTAL, Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL)
        val fit = image.zoomable.minScaleState.value
        scaleTo(fit * 1.8f * 1.8f)
        longPress()
        assertEquals(fit * 1.8f, image.zoomable.transformState.value.scaleX, 0.01f)
        longPress()
        assertEquals(fit, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    @Test
    fun `incremental shrink below one step falls back to the initial scale`() {
        Shaft.sSettings.customZoomAddScale = 1.8f
        attach(Settings.DOUBLE_TAP_ZOOM_MODE_INCREMENTAL, Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL)
        val fit = image.zoomable.minScaleState.value
        scaleTo(fit * 1.2f)
        longPress()
        assertEquals(fit, image.zoomable.transformState.value.scaleX, 0.01f)
    }

    private fun attach(doubleTapMode: Int, longPressBehavior: Int) {
        Shaft.sSettings.doubleTapZoomMode = doubleTapMode
        Shaft.sSettings.longPressBehavior = longPressBehavior
        val container = FrameLayout(host.get()).apply { id = View.generateViewId() }
        host.get().setContentView(container)
        fragment = FragmentImageDetail.newInstance("content://zoom-test/still")
        host.get().supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
        image = fragment.requireView().findViewById(R.id.image)
        image.disposeLoad()
        assertEquals(View.VISIBLE, image.visibility)
        image.layoutParams = android.widget.RelativeLayout.LayoutParams(400, 600)
        image.setImageBitmap(bitmap(800, 400))
        image.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        image.layout(0, 0, 400, 600)
        idle()
    }

    /** 直接摆到指定倍率，返回实际生效值（会被 maxScale 夹住）。 */
    private fun scaleTo(targetScale: Float): Float {
        runBlocking { image.zoomable.scale(targetScale = targetScale, animated = false) }
        idle()
        return image.zoomable.transformState.value.scaleX
    }

    /**
     * 按住不动越过 `ViewConfiguration` 的长按阈值（500ms）再松手，然后等缩放动画跑完。
     *
     * `idle(700)` 会推进 Robolectric 的主线程时钟，`GestureDetector` 那条延时 500ms 的长按消息就是在这时投递的。
     */
    private fun longPress() {
        val start = SystemClock.uptimeMillis()
        event(start, 0, MotionEvent.ACTION_DOWN, 200f, 300f)
        idle(700)
        event(start, 700, MotionEvent.ACTION_UP, 200f, 300f)
        idle(600)
    }

    private fun bitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
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
