package ceui.lisa.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import ceui.lisa.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpgradeRequiredPromptTest {
    class StoreActivity : FragmentActivity() {
        val opened = mutableListOf<Intent>()
        var noStore = false
        override fun startActivity(intent: Intent) {
            opened.add(intent)
            if (noStore && intent.data?.scheme == "market") throw ActivityNotFoundException()
        }
    }

    @Test
    fun `Google builds open the release listing in Play even with a debug package name`() {
        assumeTrue(BuildConfig.IS_LITE)
        Robolectric.buildActivity(StoreActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            UpgradeRequiredPrompt.open(activity)
            val intent = activity.opened.single()
            assertEquals("market://details?id=ceui.pixiv.pshaft", intent.dataString)
            assertEquals("com.android.vending", intent.`package`)
        }
    }

    @Test
    fun `Google builds without Play use the web store listing`() {
        assumeTrue(BuildConfig.IS_LITE)
        Robolectric.buildActivity(StoreActivity::class.java).setup().use { controller ->
            val activity = controller.get().apply { noStore = true }
            UpgradeRequiredPrompt.open(activity)
            assertEquals(2, activity.opened.size)
            val intent = activity.opened.last()
            assertEquals("https://play.google.com/store/apps/details?id=ceui.pixiv.pshaft", intent.dataString)
            assertNull(intent.`package`)
        }
    }
}
