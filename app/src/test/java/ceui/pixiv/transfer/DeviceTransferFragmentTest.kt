package ceui.pixiv.transfer

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceTransferFragmentTest {
    @Test
    fun `error screen refreshes when a retry produces a different receive error`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.create().start().resume()
        try {
            val activity = controller.get()
            val fragment = DeviceTransferFragment()
            activity.supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment, "transfer")
                .commitNow()
            shadowOf(Looper.getMainLooper()).idle()

            val model = ViewModelProvider(fragment)[DeviceTransferViewModel::class.java]
            model.scanFailed(R.string.transfer_error_code)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                collectTexts(fragment.requireView()).contains(activity.getString(R.string.transfer_error_code)),
            )

            model.scanFailed(R.string.transfer_error_camera_permission)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "Expected error message to update after a different error in the same phase/role",
                collectTexts(fragment.requireView()).contains(
                    activity.getString(R.string.transfer_error_camera_permission),
                ),
            )
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun collectTexts(view: View): List<String> {
        val result = mutableListOf<String>()
        if (view is TextView && !view.text.isNullOrEmpty()) {
            result += view.text.toString()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                result += collectTexts(view.getChildAt(index))
            }
        }
        return result
    }
}
