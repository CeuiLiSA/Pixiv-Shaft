package ceui.pixiv.ui.comic.reader

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class ComicWindowControllerTest {
    private fun withActivity(test: (Activity) -> Unit) {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            test(activityController.get())
        } finally {
            activityController.pause().stop().destroy()
        }
    }

    private fun controller(activity: Activity, state: Bundle? = null) =
        ComicWindowController(activity, View(activity), View(activity), state)

    @Test
    fun `wide and tall pages choose the matching screen axis`() = withActivity { activity ->
        val controller = controller(activity)
        controller.applyImageOrientation(1600, 900)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        controller.applyImageOrientation(900, 1600)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
    }

    @Test
    fun `near square and unreadable images keep the current orientation`() = withActivity { activity ->
        val controller = controller(activity)
        controller.applyImageOrientation(1600, 900)
        listOf(1000 to 1000, 1000 to 1099, 1099 to 1000, -1 to -1, 0 to 800).forEach { (w, h) ->
            controller.applyImageOrientation(w, h)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        }
    }

    @Test
    fun `disabling after configuration recreation restores the original request`() = withActivity { activity ->
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val first = controller(activity)
        first.applyImageOrientation(1600, 900)
        val state = Bundle().also(first::saveState)
        val restored = controller(activity, state)
        restored.applyImageOrientation(900, 1600)
        restored.restoreOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test
    fun `restoring a state from before this feature keeps the original request`() = withActivity { activity ->
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = controller(activity, Bundle())
        controller.applyImageOrientation(1600, 900)
        controller.restoreOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test
    fun `disabled feature never changes the host orientation`() = withActivity { activity ->
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        val controller = controller(activity)
        controller.restoreOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, activity.requestedOrientation)
    }
}
