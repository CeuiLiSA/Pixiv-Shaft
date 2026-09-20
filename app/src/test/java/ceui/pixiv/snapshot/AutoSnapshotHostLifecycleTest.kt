package ceui.pixiv.snapshot

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import ceui.pixiv.utils.isHostStillResumed
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class AutoSnapshotHostLifecycleTest {
    class Page : Fragment() {
        val hostStatesOnPause = mutableListOf<Boolean>()
        override fun onPause() {
            hostStatesOnPause += isHostStillResumed()
            super.onPause()
        }
    }

    @Test
    fun `an activity overlay pauses the host before the page`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val page = Page()
        controller.get().supportFragmentManager.beginTransaction().add(page, "page").commitNow()
        controller.pause()
        assertEquals(listOf(false), page.hostStatesOnPause)
        controller.stop().destroy()
    }

    @Test
    fun `pager lifecycle downgrade leaves the host resumed`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val manager = controller.get().supportFragmentManager
        val page = Page()
        manager.beginTransaction().add(page, "page").commitNow()
        manager.beginTransaction().setMaxLifecycle(page, Lifecycle.State.STARTED).commitNow()
        assertEquals(listOf(true), page.hostStatesOnPause)
        controller.pause().stop().destroy()
    }
}
