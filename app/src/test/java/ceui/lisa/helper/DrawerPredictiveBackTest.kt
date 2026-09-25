package ceui.lisa.helper

import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.drawerlayout.widget.DrawerLayout
import java.time.Duration
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
 * 真进度一到就立刻跟手,兜底动画不能再覆盖它。
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

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun expectedTranslation(progress: Float) =
        -WIDTH * DecelerateInterpolator().getInterpolation(progress)

    @Test
    fun `fallback slides to fixed progress and ignores zero stubs`() {
        back.onStarted(fallback = true)
        idle()
        back.onProgressed(0f)
        back.onProgressed(0f)
        back.onProgressed(0f)
        assertEquals(expectedTranslation(0.35f), drawerView.translationX, 0.5f)
    }

    @Test
    fun `real progress during fallback animation takes over immediately`() {
        back.onStarted(fallback = true)
        // 兜底动画还没跑完,真进度就到了
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
