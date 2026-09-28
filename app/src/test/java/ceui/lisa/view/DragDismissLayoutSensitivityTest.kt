package ceui.lisa.view

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import ceui.lisa.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 手势层的三个灵敏度阈值：默认值、可被宿主覆盖、覆盖后真的改变松手判定，
 * 以及与 `Settings` 默认值的一致性。
 *
 * 「可注入」是本次为「大图拖动退出控制」开的孔——之前是 `private const val`，
 * 编译期内联，宿主改不动。这里既钉住默认常量（防止调默认值时只改一边），
 * 也确认注入的阈值真的进了 `ACTION_UP` 的判定，而不只是存了个属性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class DragDismissLayoutSensitivityTest {

    @Test
    fun `默认常量就是原有手感`() {
        assertEquals(0.18f, DragDismissLayout.DEFAULT_DISMISS_DISTANCE_FRACTION, 0f)
        assertEquals(1200f, DragDismissLayout.DEFAULT_FLING_DISMISS_VELOCITY_DP, 0f)
        assertEquals(0.3f, DragDismissLayout.DEFAULT_MAX_DRAG_SCALE_SHRINK, 0f)
    }

    @Test
    fun `新建实例取默认常量`() {
        val layout = newLayout()

        assertEquals(
            DragDismissLayout.DEFAULT_DISMISS_DISTANCE_FRACTION,
            layout.dismissDistanceFraction,
            0f,
        )
        assertEquals(
            DragDismissLayout.DEFAULT_FLING_DISMISS_VELOCITY_DP,
            layout.flingDismissVelocityDp,
            0f,
        )
        assertEquals(
            DragDismissLayout.DEFAULT_MAX_DRAG_SCALE_SHRINK,
            layout.maxDragScaleShrink,
            0f,
        )
    }

    @Test
    fun `阈值可被宿主覆盖，且互不影响`() {
        val layout = newLayout()
        layout.dismissDistanceFraction = 0.05f
        layout.flingDismissVelocityDp = 300f
        layout.maxDragScaleShrink = 0f

        assertEquals(0.05f, layout.dismissDistanceFraction, 0f)
        assertEquals(300f, layout.flingDismissVelocityDp, 0f)
        assertEquals(0f, layout.maxDragScaleShrink, 0f)
    }

    /** 阈值是实例属性而非 companion 共享状态：一个页面调过，不该污染下一个实例。 */
    @Test
    fun `实例之间不共享阈值`() {
        val tuned = newLayout()
        val fresh = newLayout()

        tuned.dismissDistanceFraction = 0.05f
        tuned.flingDismissVelocityDp = 300f
        tuned.maxDragScaleShrink = 0f

        assertEquals(
            DragDismissLayout.DEFAULT_DISMISS_DISTANCE_FRACTION,
            fresh.dismissDistanceFraction,
            0f,
        )
        assertEquals(
            DragDismissLayout.DEFAULT_FLING_DISMISS_VELOCITY_DP,
            fresh.flingDismissVelocityDp,
            0f,
        )
        assertEquals(
            DragDismissLayout.DEFAULT_MAX_DRAG_SCALE_SHRINK,
            fresh.maxDragScaleShrink,
            0f,
        )
    }

    /**
     * 两侧默认值必须一致：`Settings` 是「用户没调过」时的落库值，`DragDismissLayout` 是
     * 「没人覆盖」时的运行时值。任何一边单独改动，都会让「没调过的用户」和「点了恢复默认
     * 的用户」拿到两套手感。
     */
    @Test
    fun `Settings 默认值与手势层常量一致`() {
        assertEquals(
            DragDismissLayout.DEFAULT_DISMISS_DISTANCE_FRACTION,
            Settings.VIEWER_DISMISS_DISTANCE_DEFAULT,
            0f,
        )
        assertEquals(
            DragDismissLayout.DEFAULT_FLING_DISMISS_VELOCITY_DP,
            Settings.VIEWER_DISMISS_VELOCITY_DEFAULT,
            0f,
        )
        assertEquals(
            DragDismissLayout.DEFAULT_MAX_DRAG_SCALE_SHRINK,
            Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT,
            0f,
        )
    }

    /** 同一段位移，阈值放宽后才算「拖够了」——证明注入的值真的进了松手判定。 */
    @Test
    fun `注入的阈值真的改变松手判定`() {
        // 屏高 2000，拖 300（15%）：默认 18% 阈值下不够，调成 10% 就够。
        val strict = newLayout().apply { dismissDistanceFraction = 0.18f }
        assertFalse(dragDownBy(strict, 300f))

        val loose = newLayout().apply { dismissDistanceFraction = 0.10f }
        assertTrue(dragDownBy(loose, 300f))
    }

    // ---- helpers ----------------------------------------------------------

    private fun newLayout(): DragDismissLayout =
        DragDismissLayout(
            ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme),
        )

    /**
     * 模拟一次慢速竖向拖拽并返回松手时 [DragDismissLayout.Callback.onDismissDragRelease] 的
     * `shouldDismiss`。事件跨度 1s，速度远低于甩出阈值，所以结论只由距离阈值决定。
     *
     * 子 view 必须消费 DOWN，否则 ViewGroup 的 `mFirstTouchTarget` 为空，后续 MOVE 根本不会
     * 走到 `onInterceptTouchEvent`，手势层也就没机会接管。
     */
    private fun dragDownBy(layout: DragDismissLayout, dy: Float): Boolean {
        val consumer =
            object : View(layout.context) {
                override fun onTouchEvent(event: MotionEvent): Boolean = true
            }
        layout.addView(
            consumer,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        layout.dragTargetView = consumer

        // 必须真的 measure + layout：子 view 没有尺寸时触摸点落不进它的 bounds，
        // ViewGroup 就不会设 mFirstTouchTarget，后续 MOVE 也走不到 onInterceptTouchEvent，
        // 手势层根本没机会接管。
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        layout.layout(0, 0, WIDTH, HEIGHT)

        var dismissed = false
        layout.callback =
            object : DragDismissLayout.Callback {
                override fun canStartDismissDrag(direction: DragDismissLayout.Direction): Boolean = true

                override fun onDismissDragUpdate(fraction: Float) = Unit

                override fun onDismissDragRelease(
                    shouldDismiss: Boolean,
                    direction: DragDismissLayout.Direction,
                    velocityY: Float,
                ) {
                    dismissed = shouldDismiss
                }
            }

        val x = WIDTH / 2f
        val startY = HEIGHT / 4f
        val slop = ViewConfiguration.get(layout.context).scaledTouchSlop.toFloat()
        val interceptY = startY + slop + 1f
        val endY = interceptY + dy

        // 整段拖拽拉到 2s 走完，松手时速度远低于甩出阈值，结论只由距离阈值决定。
        layout.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0L, x, startY))
        layout.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 100L, x, interceptY))
        layout.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 2000L, x, endY))
        layout.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 2000L, x, endY))

        return dismissed
    }

    private fun event(action: Int, eventTime: Long, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, eventTime, action, x, y, 0)

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2000
    }
}
