package ceui.lisa.helper

import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.drawerlayout.widget.DrawerLayout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 抽屉预测式返回「固定比例兜底」的状态契约:兜底期间 0 进度不能把抽屉拉回;
 * 真进度一到就立刻跟手,尚未开始 / 正在进行的兜底动画都不能再覆盖它。
 *
 * Robolectric 的 idle 会把整段动画(含 startDelay)一口气跑完,没法停在「等待中」或
 * 「滑到一半」;这里只断言与时刻无关的终态,等待时长本身交给 ValueAnimator.startDelay。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrawerPredictiveBackTest {

    private lateinit var drawerView: View
    private lateinit var back: DrawerPredictiveBack

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val drawerLayout = DrawerLayout(context)
        drawerView = View(context)
        drawerView.layoutParams = DrawerLayout.LayoutParams(WIDTH, WIDTH, Gravity.START)
        drawerLayout.addView(drawerView)
        drawerView.layout(0, 0, WIDTH, WIDTH)
        back = DrawerPredictiveBack(drawerLayout, drawerView)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun expectedTranslation(progress: Float) =
        -WIDTH * DecelerateInterpolator().getInterpolation(progress)

    @Test
    fun `fallback settles at fixed progress and ignores zero stubs`() {
        back.onStarted(fallback = true)
        idle()
        back.onProgressed(0f)
        back.onProgressed(0f)
        back.onProgressed(0f)
        assertEquals(expectedTranslation(0.35f), drawerView.translationX, 0.5f)
    }

    @Test
    fun `real progress before fallback kicks in keeps tracking the finger`() {
        // 正常设备冷启动后的首个手势:开头的 0 样本之后,真进度在兜底开始前就到了
        back.onStarted(fallback = true)
        back.onProgressed(0f)
        back.onProgressed(0.1f)
        idle()
        assertEquals(expectedTranslation(0.1f), drawerView.translationX, 0.5f)
    }

    @Test
    fun `without fallback zero progress keeps drawer in place`() {
        back.onStarted()
        idle()
        back.onProgressed(0f)
        assertEquals(0f, drawerView.translationX, 0.5f)
    }

    private companion object {
        const val WIDTH = 1000
    }
}
