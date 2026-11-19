package ceui.pixiv.ui.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import androidx.viewbinding.ViewBinding
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import com.google.gson.Gson
import com.hjq.toast.Toaster
import com.hjq.toast.config.IToastInterceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManageStorageSettingsTest {

    private var prevSettings: Settings? = null
    private var prevGson: Gson? = null
    private var prevPrefs: SharedPreferences? = null
    private var prevToastInterceptor: IToastInterceptor? = null

    class DummyBinding(private val rootView: View) : ViewBinding {
        override fun getRoot(): View = rootView
    }

    /** 最小可实例化 BaseActivity，暴露 onActivityResult 以验证 SAF 拦截器生命周期时序。 */
    class TestBaseActivity : BaseActivity<DummyBinding>() {
        override fun initLayout(): Int = android.R.layout.list_content
        override fun bindView(root: View): DummyBinding = DummyBinding(FrameLayout(this))
        override fun initView() = Unit
        override fun initData() = Unit

        fun dispatchActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            onActivityResult(requestCode, resultCode, data)
        }
    }

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        prevSettings = Shaft.sSettings
        prevGson = Shaft.sGson
        prevPrefs = Shaft.sPreferences
        Shaft.sSettings = Settings()
        Shaft.sGson = Gson()
        Shaft.sPreferences = app.getSharedPreferences("manage_storage_test_prefs", Context.MODE_PRIVATE)
        Toaster.init(app)
        prevToastInterceptor = Toaster.getInterceptor()
        Toaster.setInterceptor { true }
    }

    @After
    fun tearDown() {
        Toaster.setInterceptor(prevToastInterceptor)
        Shaft.sSettings = prevSettings
        Shaft.sGson = prevGson
        Shaft.sPreferences = prevPrefs
    }

    @Test
    fun `formatSafTreePath resolves primary, home, sdcard and raw downloads paths`() {
        val extRoot = "/storage/emulated/0"
        val docsDir = "/storage/emulated/0/Documents"

        assertEquals(
            "/storage/emulated/0/Pictures/Shaft",
            ManageStorageSettingsFragment.formatSafTreePath(
                uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FShaft"),
                externalStorageRoot = extRoot,
                documentsDir = docsDir,
                fallbackDisplayName = { null },
            ),
        )
        assertEquals(
            "/storage/emulated/0",
            ManageStorageSettingsFragment.formatSafTreePath(
                uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A"),
                externalStorageRoot = extRoot,
                documentsDir = docsDir,
                fallbackDisplayName = { null },
            ),
        )
        assertEquals(
            "/storage/emulated/0/Documents/Pixiv",
            ManageStorageSettingsFragment.formatSafTreePath(
                uri = Uri.parse("content://com.android.externalstorage.documents/tree/home%3APixiv"),
                externalStorageRoot = extRoot,
                documentsDir = docsDir,
                fallbackDisplayName = { null },
            ),
        )
        assertEquals(
            "/storage/1234-5678/Pixiv/Art",
            ManageStorageSettingsFragment.formatSafTreePath(
                uri = Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3APixiv%2FArt"),
                externalStorageRoot = extRoot,
                documentsDir = docsDir,
                fallbackDisplayName = { null },
            ),
        )
        assertEquals(
            "/storage/emulated/0/Download/Pixiv",
            ManageStorageSettingsFragment.formatSafTreePath(
                uri = Uri.parse("content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FPixiv"),
                externalStorageRoot = extRoot,
                documentsDir = docsDir,
                fallbackDisplayName = { null },
            ),
        )
    }

    @Test
    fun `formatSafTreePath does not fabricate storage path for opaque or cloud providers`() {
        val extRoot = "/storage/emulated/0"
        val docsDir = "/storage/emulated/0/Documents"

        assertEquals(
            "MyCloudFolder",
            ManageStorageSettingsFragment.formatSafTreePath(
                uri = Uri.parse("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Afolder"),
                externalStorageRoot = extRoot,
                documentsDir = docsDir,
                fallbackDisplayName = { "MyCloudFolder" },
            ),
        )
        assertEquals(
            "Downloads",
            ManageStorageSettingsFragment.formatSafTreePath(
                uri = Uri.parse("content://com.android.providers.downloads.documents/tree/msd%3A123"),
                externalStorageRoot = extRoot,
                documentsDir = docsDir,
                fallbackDisplayName = { "Downloads" },
            ),
        )
    }

    @Test
    fun `instance SafGrantInterceptor survives onStop before onActivityResult and isolates activities`() {
        val controllerA = Robolectric.buildActivity(TestBaseActivity::class.java).create().start().resume()
        val controllerB = Robolectric.buildActivity(TestBaseActivity::class.java).create().start().resume()
        val activityA = controllerA.get()
        val activityB = controllerB.get()

        var receivedByA: Uri? = null
        var receivedByB: Uri? = null
        val interceptorA = BaseActivity.SafGrantInterceptor { uri ->
            receivedByA = uri
            true
        }
        val interceptorB = BaseActivity.SafGrantInterceptor { uri ->
            receivedByB = uri
            true
        }

        activityA.setSafGrantInterceptor(interceptorA)
        activityB.setSafGrantInterceptor(interceptorB)

        // 模拟弹出系统 DocumentsUI：activityA 进入 onStop，返回时 onActivityResult 先于 onStart 投递
        controllerA.pause().stop()
        val expectedUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APixiv")
        val resultIntent = Intent().setData(expectedUri)
        activityA.dispatchActivityResult(BaseActivity.ASK_URI, Activity.RESULT_OK, resultIntent)
        controllerA.restart().start().resume()

        assertEquals(expectedUri, receivedByA)
        assertNull("activityA 的 SAF 授权不应串台到 activityB", receivedByB)

        // 用不匹配的引用 clear 不会误清其他拦截器
        activityA.clearSafGrantInterceptor(interceptorB)
        val secondUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASecond")
        activityA.dispatchActivityResult(BaseActivity.ASK_URI, Activity.RESULT_OK, Intent().setData(secondUri))
        assertEquals(secondUri, receivedByA)

        // 正确引用 clear 后不再拦截（再次回调不会触发原拦截器）
        receivedByA = null
        activityA.clearSafGrantInterceptor(interceptorA)
        activityA.dispatchActivityResult(
            BaseActivity.ASK_URI,
            Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AThird")),
        )
        assertNull(receivedByA)
    }
}
