package ceui.pixiv.ui.detail

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.FrameLayout
import ceui.lisa.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 只断言同步的状态转移（attach / 可见性切换 / detach 都是同步回调）。
 * 不测「循环模式永不自停」：Robolectric 的 ShadowValueAnimator 会把 INFINITE 截成有限次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShimmerOverlayViewTest {

    private lateinit var activity: Activity
    private lateinit var parent: FrameLayout

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        parent = FrameLayout(activity)
        activity.setContentView(parent)
        // Robolectric 的 ViewRootImpl 默认 mAppVisible=false，窗口可见性恒为 GONE；
        // 真机上由 WMS 派发这一步，这里手动补上，聚合可见性才会按真实设备那样回调
        Class.forName("android.view.ViewRootImpl")
            .getMethod("dispatchAppVisibility", Boolean::class.javaPrimitiveType)
            .invoke(activity.window.decorView.parent, true)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
    }

    private fun shimmer(loop: Boolean?): ShimmerOverlayView {
        val attrs = Robolectric.buildAttributeSet().apply {
            if (loop != null) addAttribute(R.attr.shimmerLoop, loop.toString())
        }.build()
        return ShimmerOverlayView(activity, attrs)
    }

    private fun attach(view: View) {
        parent.addView(view, FrameLayout.LayoutParams(200, 100))
    }

    @Test
    fun loopModeIsTheDefaultAndRunsOnlyWhileVisible() {
        val view = shimmer(loop = null)
        attach(view)
        assertTrue(view.isShimmerRunning)

        parent.visibility = View.GONE
        assertFalse(view.isShimmerRunning)

        parent.visibility = View.VISIBLE
        assertTrue(view.isShimmerRunning)

        parent.removeView(view)
        assertFalse(view.isShimmerRunning)
    }

    @Test
    fun onceModeSweepsOncePerAttach() {
        val view = shimmer(loop = false)
        attach(view)
        assertTrue(view.isShimmerRunning)

        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        assertFalse("单次模式扫完必须停下，不能持续重绘", view.isShimmerRunning)

        // 同一次挂载内的可见性来回切换不重扫
        parent.visibility = View.GONE
        parent.visibility = View.VISIBLE
        assertFalse(view.isShimmerRunning)

        // 重新挂载（RecyclerView 滑出再滑回）会再扫一遍
        parent.removeView(view)
        attach(view)
        assertTrue(view.isShimmerRunning)
    }

    @Test
    fun onceModeInterruptedByHidingDoesNotRestartWithinTheSameAttach() {
        val view = shimmer(loop = false)
        attach(view)
        assertTrue(view.isShimmerRunning)

        parent.visibility = View.GONE
        assertFalse(view.isShimmerRunning)

        parent.visibility = View.VISIBLE
        assertFalse(view.isShimmerRunning)
    }

    @Test
    fun reattachingUnderAHiddenParentDoesNotStartFromStaleVisibility() {
        val view = shimmer(loop = true)
        attach(view)
        assertTrue(view.isShimmerRunning)

        parent.removeView(view)
        parent.visibility = View.GONE
        attach(view)
        assertFalse(view.isShimmerRunning)

        parent.visibility = View.VISIBLE
        assertTrue(view.isShimmerRunning)
    }
}
