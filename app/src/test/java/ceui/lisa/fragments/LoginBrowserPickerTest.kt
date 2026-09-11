package ceui.lisa.fragments

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import ceui.lisa.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 35], application = Application::class)
class LoginBrowserPickerTest {
    @Test
    fun `browser picker excludes private and permission protected activities`() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.fromParts("https", "", null)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val candidates = listOf(
            browser("org.example.browser"),
            browser("com.lu.ashionweather", exported = false),
            browser("ai.parallelworld.chat",
                permission = "com.miui.securitycenter.permission.AppPermissionsEditor"),
            browser("org.example.permitted", permission = Manifest.permission.INTERNET),
        )
        assertEquals(PackageManager.PERMISSION_GRANTED,
            pm.checkPermission(Manifest.permission.INTERNET, BuildConfig.APPLICATION_ID))
        candidates.forEach { shadowOf(pm).addResolveInfoForIntent(probe, it) }
        // 模拟 PackageManager 返回可匹配、但本应用无权启动的组件。
        assertEquals(candidates.map { it.activityInfo.packageName }.toSet(),
            pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }.toSet())

        val browsers = ReflectionHelpers.callInstanceMethod<List<Any>>(
            FragmentLogin(), "queryBrowsers", ClassParameter.from(PackageManager::class.java, pm),
        )

        assertEquals(setOf("org.example.browser", "org.example.permitted"),
            browsers.map { ReflectionHelpers.getField<String>(it, "packageName") }.toSet())
    }

    private fun browser(
        packageName: String,
        exported: Boolean = true,
        permission: String? = null,
    ) = ResolveInfo().apply {
        nonLocalizedLabel = packageName
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.WebActivity"
            this.exported = exported
            this.permission = permission
            enabled = true
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                enabled = true
            }
        }
    }
}
