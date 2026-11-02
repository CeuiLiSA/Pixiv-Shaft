package ceui.pixiv.ui.settings

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import ceui.lisa.utils.Settings
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28, 35],
    application = Application::class,
    shadows = [ChromeTabsPrefsTest.MemoryMMKV::class],
    instrumentedPackages = ["com.tencent.mmkv"]
)
class ChromeTabsPrefsTest {

    @Before
    fun setUp() {
        MemoryMMKV.values.clear()
    }

    private fun installChrome(enabled: Boolean = true) {
        val app = RuntimeEnvironment.getApplication()
        val pm = app.packageManager
        val pi = PackageInfo().apply {
            packageName = ChromeTabsPrefs.CHROME_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = ChromeTabsPrefs.CHROME_PACKAGE
                this.enabled = enabled
            }
        }
        shadowOf(pm).installPackage(pi)
    }

    @Test
    fun `context 为 null 时安全返回 false`() {
        assertFalse(ChromeTabsPrefs.isChromeInstalled(null))
        assertFalse(ChromeTabsPrefs.shouldUseChromeCustomTabs(null))
    }

    @Test
    fun `默认开启，但无 Chrome 时自动禁用`() {
        val context = RuntimeEnvironment.getApplication()

        // 默认状态：用户从未手动关闭过
        assertFalse(ChromeTabsPrefs.isUserDisabled())
        // 没有安装 Chrome
        assertFalse(ChromeTabsPrefs.isChromeInstalled(context))
        // 不应该使用 Chrome Custom Tabs
        assertFalse(ChromeTabsPrefs.shouldUseChromeCustomTabs(context))
    }

    @Test
    fun `默认开启，安装 Chrome 后自动启用`() {
        val context = RuntimeEnvironment.getApplication()
        installChrome()

        assertFalse(ChromeTabsPrefs.isUserDisabled())
        assertTrue(ChromeTabsPrefs.isChromeInstalled(context))
        assertTrue(ChromeTabsPrefs.shouldUseChromeCustomTabs(context))
    }

    @Test
    fun `处于自动禁用状态下，用户新装 Chrome 后自动启用`() {
        val context = RuntimeEnvironment.getApplication()
        // 起初没有 Chrome（自动禁用）
        assertFalse(ChromeTabsPrefs.isChromeInstalled(context))
        assertFalse(ChromeTabsPrefs.shouldUseChromeCustomTabs(context))

        // 用户新装了 Chrome
        installChrome()
        // 自动启用（因为用户从未手动关闭过）
        assertTrue(ChromeTabsPrefs.isChromeInstalled(context))
        assertTrue(ChromeTabsPrefs.shouldUseChromeCustomTabs(context))
    }

    @Test
    fun `用户手动关闭后，即使有 Chrome 也不启用`() {
        val context = RuntimeEnvironment.getApplication()
        installChrome()

        // 用户主动关闭
        ChromeTabsPrefs.setUserDisabled(true)
        assertTrue(ChromeTabsPrefs.isUserDisabled())
        // 哪怕有 Chrome，也不再启用
        assertFalse(ChromeTabsPrefs.shouldUseChromeCustomTabs(context))

        // 模拟后续冷启动检测：状态依然保持手动关闭
        assertTrue(ChromeTabsPrefs.isUserDisabled())
        assertFalse(ChromeTabsPrefs.shouldUseChromeCustomTabs(context))
    }

    @Test
    fun `用户手动重新开启后恢复启用`() {
        val context = RuntimeEnvironment.getApplication()
        installChrome()

        ChromeTabsPrefs.setUserDisabled(true)
        assertFalse(ChromeTabsPrefs.shouldUseChromeCustomTabs(context))

        ChromeTabsPrefs.setUserDisabled(false)
        assertFalse(ChromeTabsPrefs.isUserDisabled())
        assertTrue(ChromeTabsPrefs.shouldUseChromeCustomTabs(context))
    }

    @Test
    fun `不进备份与云端 —— Settings 序列化不含该选项`() {
        ChromeTabsPrefs.setUserDisabled(true)
        val json = Gson().toJson(Settings())
        assertFalse(json, json.contains("chrome_custom_tabs"))
        assertFalse(json, json.contains("cct_user_disabled"))
        assertFalse(json, json.contains("chrome_tabs_prefs"))
        assertFalse(json, json.contains("chromeCustomTabs"))
    }

    @Implements(MMKV::class, isInAndroidSdk = false)
    class MemoryMMKV {
        @Implementation
        fun count(): Long = values.size.toLong()

        @Implementation
        fun containsKey(key: String): Boolean = values.containsKey(key)

        @Implementation
        fun removeValueForKey(key: String) {
            values.remove(key)
        }

        @Implementation
        fun decodeFloat(key: String, defaultValue: Float): Float =
            values[key] as? Float ?: defaultValue

        @Implementation
        fun decodeInt(key: String, defaultValue: Int): Int =
            values[key] as? Int ?: defaultValue

        @Implementation
        fun decodeBool(key: String, defaultValue: Boolean): Boolean =
            values[key] as? Boolean ?: defaultValue

        @Implementation
        fun decodeString(key: String, defaultValue: String?): String? =
            values[key] as? String ?: defaultValue

        @Implementation
        fun encode(key: String, value: Float): Boolean {
            values[key] = value
            return true
        }

        @Implementation
        fun encode(key: String, value: Boolean): Boolean {
            values[key] = value
            return true
        }

        @Implementation
        fun encode(key: String, value: String): Boolean {
            values[key] = value
            return true
        }

        @Implementation
        fun encode(key: String, value: Int): Boolean {
            values[key] = value
            return true
        }

        @Implementation
        fun encode(key: String, value: Long): Boolean {
            values[key] = value
            return true
        }

        companion object {
            val values = mutableMapOf<String, Any>()

            @JvmStatic
            @Implementation
            fun mmkvWithID(id: String): MMKV = ReflectionHelpers.callConstructor(
                MMKV::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L),
            )
        }
    }
}
