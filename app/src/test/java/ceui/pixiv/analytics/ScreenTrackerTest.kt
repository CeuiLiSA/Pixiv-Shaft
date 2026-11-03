package ceui.pixiv.analytics

import android.app.Activity
import android.app.Application
import ceui.pixiv.ui.navigation.TemplateRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 入口归因（surface）的口径：浏览页更新它，内容页沿用打开它的那个浏览页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScreenTrackerTest {

    class Screen : Activity(), AnalyticsScreen {
        var name: String = ""
        override fun analyticsScreenName(): String = name
    }

    private lateinit var tracker: ScreenTracker

    @Before
    fun setUp() {
        // Firebase 没初始化：Analytics 只走页面状态，不上报。
        tracker = ScreenTracker(Analytics(RuntimeEnvironment.getApplication()))
    }

    private fun screen(name: String): Screen =
        Robolectric.buildActivity(Screen::class.java).get().also { it.name = name }

    private fun show(activity: Activity) {
        tracker.onActivityResumed(activity)
    }

    private fun leave(activity: Activity) {
        tracker.onActivityPaused(activity)
    }

    @Test
    fun `content page keeps the surface it was opened from`() {
        val home = screen("home_recommend")
        val detail = screen("illust_detail")
        val profile = screen("user_profile")

        show(home)
        leave(home)
        show(detail)
        assertEquals("home_recommend", tracker.surface)

        // 详情 → 作者主页 → 返回详情：收藏仍归推荐流。
        leave(detail)
        show(profile)
        assertEquals("user_profile", tracker.surface)
        leave(profile)
        show(detail)
        assertEquals("home_recommend", tracker.surface)
    }

    @Test
    fun `content opened from content inherits the original surface`() {
        val ranking = screen("ranking")
        val detail = screen("illust_detail")
        val comments = screen(TemplateRoute.COMMENTS.screenName)

        show(ranking)
        leave(ranking)
        show(detail)
        leave(detail)
        show(comments)
        assertEquals("ranking", tracker.surface)
    }

    @Test
    fun `browse page always becomes the surface`() {
        val home = screen("home_recommend")
        show(home)
        home.name = "home_following"
        tracker.onScreenChanged(home)
        assertEquals("home_following", tracker.surface)
        assertEquals("home_following", tracker.currentScreen)
    }

    @Test
    fun `content opened cold has no surface`() {
        show(screen("illust_detail"))
        assertNull(tracker.surface)
    }
}
