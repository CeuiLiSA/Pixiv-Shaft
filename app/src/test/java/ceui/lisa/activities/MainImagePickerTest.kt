package ceui.lisa.activities

import android.app.Application
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.descendants
import ceui.lisa.R
import ceui.lisa.utils.Params
import com.hjq.toast.Toaster
import com.hjq.toast.config.IToastInterceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 35], application = Application::class)
class MainImagePickerTest {
    private lateinit var activity: PickerActivity
    private var toast: CharSequence? = null
    private var previousToastInterceptor: IToastInterceptor? = null

    @Before
    fun setUp() {
        Toaster.init(RuntimeEnvironment.getApplication())
        previousToastInterceptor = Toaster.getInterceptor()
        Toaster.setInterceptor { params ->
            toast = params.text
            true
        }
        // 不启动首页的数据加载，只运行真实选图弹窗及其点击回调。
        activity = Robolectric.buildActivity(PickerActivity::class.java).get()
        activity.setTheme(R.style.AppTheme)
        ReflectionHelpers.setField(activity, "mActivity", activity)
    }

    @After
    fun tearDown() {
        Toaster.setInterceptor(previousToastInterceptor)
    }

    @Test
    fun `gallery works without a legacy ACTION_PICK handler`() {
        val dialog = choose(0)
        val intent = activity.launchedIntent!!
        assertEquals(if (Build.VERSION.SDK_INT >= 33) MediaStore.ACTION_PICK_IMAGES
            else Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("image/*", intent.type)
        assertEquals(Params.REQUEST_CODE_CHOOSE, activity.launchedRequestCode)
        assertFalse(dialog.isShowing)
        assertEquals(null, toast)
    }

    @Test
    fun `file option still opens an image document`() {
        choose(1)
        val intent = activity.launchedIntent!!
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("image/*", intent.type)
        assertEquals(Params.REQUEST_CODE_CHOOSE, activity.launchedRequestCode)
    }

    @Test
    fun `missing or blocked picker reports failure for both menu options`() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            for (which in 0..1) {
                toast = null
                activity.launchFailure = failure
                val dialog = choose(which)
                assertEquals(activity.getString(R.string.string_262), toast?.toString())
                assertFalse(dialog.isShowing)
            }
        }
    }

    private fun choose(which: Int): Dialog {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "selectPhoto")
        val dialog = ShadowDialog.getLatestDialog()
        val label = (dialog.window!!.decorView as ViewGroup).descendants
            .filterIsInstance<TextView>()
            .single { it.text.toString() == MainActivity.ALL_SELECT_WAY[which] }
        assertTrue((label.parent as View).performClick())
        return dialog
    }

    class PickerActivity : MainActivity() {
        var launchedIntent: Intent? = null
        var launchedRequestCode: Int? = null
        var launchFailure: RuntimeException? = null

        override fun startActivityForResult(intent: Intent, requestCode: Int) {
            launchedIntent = intent
            launchedRequestCode = requestCode
            launchFailure?.let { throw it }
            if (intent.action == Intent.ACTION_PICK) {
                throw ActivityNotFoundException("No legacy gallery installed")
            }
        }
    }
}
