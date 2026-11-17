package ceui.pixiv.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Looper
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class LikeBurstViewTest {

    @Test
    fun `play forwards start and end once and lets the caller hide the burst`() {
        val view = newView()
        val events = mutableListOf<String>()
        view.addAnimatorListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                events += "start"
            }

            override fun onAnimationEnd(animation: Animator) {
                events += "end"
                view.visibility = View.GONE
            }
        })

        view.playAnimation()
        assertEquals(View.VISIBLE, view.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(listOf("start", "end"), events)
        assertEquals(View.GONE, view.visibility)
    }

    @Test
    fun `replaying cancels the previous burst and completes the new one`() {
        val view = newView()
        val events = mutableListOf<String>()
        view.addAnimatorListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                events += "start"
            }

            override fun onAnimationCancel(animation: Animator) {
                events += "cancel"
            }

            override fun onAnimationEnd(animation: Animator) {
                events += "end"
                view.visibility = View.GONE
            }
        })

        view.playAnimation()
        view.playAnimation()
        assertEquals(View.VISIBLE, view.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(listOf("start", "cancel", "end", "start", "end"), events)
        assertEquals(View.GONE, view.visibility)
    }

    private fun newView() = LikeBurstView(
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme),
    )
}
