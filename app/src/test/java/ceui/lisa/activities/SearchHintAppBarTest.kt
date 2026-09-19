package ceui.lisa.activities

import android.app.Activity
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import ceui.lisa.R
import ceui.lisa.databinding.FragmentNewSearchBinding
import com.blankj.utilcode.util.Utils
import com.google.android.material.appbar.AppBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchHintAppBarTest {
    private lateinit var binding: FragmentNewSearchBinding
    private lateinit var search: SearchActivity
    private lateinit var behavior: AppBarLayout.Behavior
    private var originalFlags = 0

    @Before
    fun setUp() {
        // Keep animations between frames instead of Robolectric auto-draining them on idle.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Utils.init(RuntimeEnvironment.getApplication())
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(host, R.style.AppTheme)
        binding = FragmentNewSearchBinding.inflate(LayoutInflater.from(context))
        binding.searchTagsFlow.showRemoveIcon = true
        host.setContentView(binding.root)
        // Exercise the real search layout and animation without login, network or native storage.
        search = Robolectric.buildActivity(SearchActivity::class.java).get()
        ReflectionHelpers.setField(search, "baseBind", binding)
        originalFlags = (binding.toolbar.layoutParams as AppBarLayout.LayoutParams).scrollFlags
        ReflectionHelpers.setField(search, "mToolbarScrollFlags", originalFlags)
        layout()
        behavior = (binding.appBar.layoutParams as CoordinatorLayout.LayoutParams)
            .behavior as AppBarLayout.Behavior
        assertTrue(binding.appBar.totalScrollRange > 0)
    }

    @Test
    fun `show expands a partially collapsed bar and leaves result scrolling unconsumed`() {
        behavior.topAndBottomOffset = -binding.appBar.totalScrollRange / 2
        hints(true)
        layout()
        assertEquals(0, behavior.topAndBottomOffset)
        assertEquals(0, binding.appBar.totalScrollRange)
        assertEquals(0, scrollBar())
        assertEquals(0, behavior.topAndBottomOffset)
    }

    @Test
    fun `bar remains pinned until hide animation finishes`() {
        hints(true)
        advance(300)
        hints(false)
        layout()
        assertEquals(View.VISIBLE, binding.hintList.visibility)
        assertEquals("The fading overlay still needs its anchor", 0, scrollBar())
        assertEquals(0, behavior.topAndBottomOffset)
        advance(250)
        assertEquals(View.GONE, binding.hintList.visibility)
        assertEquals(originalFlags, (binding.toolbar.layoutParams as AppBarLayout.LayoutParams).scrollFlags)
        assertTrue("Normal scrolling resumes after hints disappear", scrollBar() > 0)
    }

    @Test
    fun `show during hide cancels stale hide and keeps bar pinned`() {
        hints(true)
        advance(300)
        hints(false)
        advance(64)
        assertEquals("Reopen before the hide animation completes", View.VISIBLE, binding.hintList.visibility)
        hints(true)
        advance(300)
        assertEquals(View.VISIBLE, binding.hintList.visibility)
        assertEquals(1f, binding.hintList.alpha, 0.001f)
        assertEquals(0, scrollBar())
        assertEquals(0, behavior.topAndBottomOffset)
    }

    @Test
    fun `hide during show restores scrolling and repeated hide is harmless`() {
        hints(true)
        advance(64)
        hints(false)
        hints(false)
        advance(300)
        assertEquals(View.GONE, binding.hintList.visibility)
        assertEquals(originalFlags, (binding.toolbar.layoutParams as AppBarLayout.LayoutParams).scrollFlags)
        assertTrue(scrollBar() > 0)
    }

    private fun hints(show: Boolean) {
        ReflectionHelpers.callInstanceMethod<Void>(search, "animateHintList",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, show))
    }

    private fun scrollBar(): Int {
        behavior.onStartNestedScroll(binding.topParent, binding.appBar, binding.drawerlayout,
            binding.viewPager, ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
        val consumed = IntArray(2)
        behavior.onNestedPreScroll(binding.topParent, binding.appBar, binding.viewPager,
            0, 20, consumed, ViewCompat.TYPE_TOUCH)
        return consumed[1]
    }

    private fun advance(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            val frame = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frame))
            remaining -= frame
        }
        layout()
    }

    private fun layout() {
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        binding.root.layout(0, 0, 1080, 1920)
    }
}
