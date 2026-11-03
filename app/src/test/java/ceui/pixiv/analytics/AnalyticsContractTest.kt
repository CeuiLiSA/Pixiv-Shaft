package ceui.pixiv.analytics

import ceui.pixiv.ui.navigation.TemplateRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 GA4 的命名限制。超限的名字 SDK 不报错，只是静默丢掉（或截断后在报表里对不上），
 * 只能在这里拦。限制见 https://support.google.com/analytics/answer/9267744
 */
class AnalyticsContractTest {

    private val snakeCase = Regex("^[a-z][a-z0-9_]*$")

    /** GA4 自动采集事件占用的名字，自定义事件不能重名。 */
    private val reservedEvents = setOf(
        "ad_activeview", "ad_click", "ad_exposure", "ad_impression", "ad_query", "ad_reward",
        "adunit_exposure", "app_background", "app_clear_data", "app_exception", "app_remove",
        "app_store_refund", "app_store_subscription_cancel", "app_store_subscription_convert",
        "app_store_subscription_renew", "app_update", "app_upgrade", "dynamic_link_app_open",
        "dynamic_link_app_update", "dynamic_link_first_open", "error", "first_open", "first_visit",
        "in_app_purchase", "notification_dismiss", "notification_foreground", "notification_open",
        "notification_receive", "os_update", "screen_view", "session_start",
        "session_start_with_rollout", "user_engagement",
    )
    private val reservedUserProps = setOf(
        "first_open_after_install", "first_open_time", "first_visit_time", "last_deep_link_referrer",
        "user_id",
    )
    private val reservedPrefixes = listOf("firebase_", "google_", "ga_")

    private fun assertName(kind: String, name: String, maxLength: Int) {
        assertTrue("$kind '$name' must be snake_case", snakeCase.matches(name))
        assertTrue("$kind '$name' exceeds $maxLength chars", name.length <= maxLength)
        assertFalse("$kind '$name' uses a reserved prefix", reservedPrefixes.any(name::startsWith))
    }

    private fun assertDistinct(kind: String, names: List<String>) {
        assertEquals("duplicate $kind: $names", names.size, names.toSet().size)
    }

    @Test
    fun `event names fit ga4 limits`() {
        Analytics.Event.ALL.forEach {
            assertName("event", it, 40)
            assertFalse("event '$it' is reserved by GA4", it in reservedEvents)
        }
        assertDistinct("event", Analytics.Event.ALL)
    }

    @Test
    fun `param names fit ga4 limits`() {
        Analytics.Param.ALL.forEach { assertName("param", it, 40) }
        assertDistinct("param", Analytics.Param.ALL)
        // 单个事件最多 25 个参数；全集都不到 25，任何组合都不会超。
        assertTrue(Analytics.Param.ALL.size < 25)
    }

    @Test
    fun `user property names fit ga4 limits`() {
        Analytics.UserProp.ALL.forEach {
            assertName("user property", it, 24)
            assertFalse("user property '$it' is reserved by GA4", it in reservedUserProps)
        }
        assertDistinct("user property", Analytics.UserProp.ALL)
        assertTrue(Analytics.UserProp.ALL.size <= 25)
    }

    @Test
    fun `enum wire values are short stable snake case`() {
        val wires: Map<String, List<String>> = mapOf(
            "ContentType" to Analytics.ContentType.entries.map { it.wire },
            "BookmarkMethod" to Analytics.BookmarkMethod.entries.map { it.wire },
            "FollowMethod" to Analytics.FollowMethod.entries.map { it.wire },
            "DownloadMethod" to Analytics.DownloadMethod.entries.map { it.wire },
            "LoginMethod" to Analytics.LoginMethod.entries.map { it.wire },
            "LogoutReason" to Analytics.LogoutReason.entries.map { it.wire },
            "SearchType" to Analytics.SearchType.entries.map { it.wire },
            "QueryKind" to Analytics.QueryKind.entries.map { it.wire },
            "SearchTrigger" to Analytics.SearchTrigger.entries.map { it.wire },
        )
        wires.forEach { (enum, values) ->
            // 用户属性值上限 36，参数值上限 100；按更严的那个卡。
            values.forEach { assertName(enum, it, 36) }
            assertDistinct(enum, values)
        }
    }

    @Test
    fun `template route screen names are valid`() {
        val names = TemplateRoute.entries.map { it.screenName }
        names.forEach { assertName("screen", it, 100) }
        assertDistinct("screen", names)
    }

    @Test
    fun `unknown activities get a snake case screen name`() {
        assertEquals("app_lock", ScreenTracker.defaultScreenName("AppLockActivity"))
        assertEquals("main", ScreenTracker.defaultScreenName("MainActivity"))
        assertEquals("u_crop", ScreenTracker.defaultScreenName("UCropActivity"))
        assertEquals("html_viewer", ScreenTracker.defaultScreenName("HTMLViewerActivity"))
        assertEquals("image_v2", ScreenTracker.defaultScreenName("ImageV2Activity"))
        assertEquals("activity", ScreenTracker.defaultScreenName("Activity"))
    }
}
