package ceui.pixiv.analytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.OutReversActivity
import ceui.lisa.activities.OutWakeActivity
import ceui.lisa.activities.RankActivity
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.UserActivityV3
import ceui.lisa.activities.VActivity
import ceui.pixiv.ui.navigation.TemplateRoute
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap

/**
 * 承载多个逻辑页面的 Activity 实现它，自报当前展示的是哪一页。页面变了之后调
 * [Analytics.screenChanged]。
 *
 * 只给「容器」用：[ceui.lisa.activities.TemplateActivity]（一个 Activity 装一百多条路由）、
 * [ceui.lisa.activities.MainActivity]（底栏 tab）。普通 Activity 的名字在 [ScreenTracker] 的表里。
 */
interface AnalyticsScreen {
    /** snake_case 的逻辑页面名，见 `docs/analytics-tracking-plan.md`；null 表示用默认名。 */
    fun analyticsScreenName(): String?
}

/** TemplateActivity 路由的页面名。常量名因此也是埋点契约：改名会让 GA4 里这一页的历史断开。 */
val TemplateRoute.screenName: String
    get() = name.lowercase(Locale.ROOT)

/**
 * 手动上报 screen_view（manifest 里关掉了 SDK 的自动上报）。
 *
 * SDK 自动上报只认 Activity 类名：首页五个 tab 都是 MainActivity，一百多个 TemplateActivity
 * 路由全挤在 "TemplateActivity" 一行里，报表上看不出任何东西。这里改成逻辑页面名：
 *  - `screen_name`：逻辑页面名（`home_recommend`、`novel_reader`、`illust_detail`…）；
 *  - `screen_class`：仍是 Activity 类名，和改造前的历史数据口径一致，按类名看的报表不断档。
 *
 * 触发时机对齐 SDK 原来的行为：每次换到另一个 Activity（含返回）记一次；同一个 Activity
 * 熄屏再亮这种「没换页」的 resume 不记。容器内换页由 [onScreenChanged] 补记。
 *
 * 顺带维护 [surface]：把用户领到当前页的那个「浏览页」。详情、阅读器、评论、大图这类**看内容**的
 * 页面不算浏览页，所以在详情里点收藏、在阅读器里点下载，带出去的都是把用户领到这里的那个列表页
 * ——这正是「哪个入口带来的转化」要的口径。
 *
 * 内容页的 surface 按**它被打开的那一刻**定，之后回到它时恢复，而不是取「最近去过的浏览页」：
 * 推荐流 → 详情 → 点进作者主页 → 返回详情再收藏，入口仍是推荐流，不是作者主页。
 */
internal class ScreenTracker(private val analytics: Analytics) : Application.ActivityLifecycleCallbacks {

    /** 当前页面名。主线程写，任意线程读。 */
    @Volatile
    var currentScreen: String? = null
        private set

    /** 把用户领到当前页的浏览页，见类注释。 */
    @Volatile
    var surface: String? = null
        private set

    private var lastActivity: WeakReference<Activity>? = null
    private var resumedActivity: WeakReference<Activity>? = null

    /** 内容页 → 打开它时的 surface，见类注释。只在主线程读写。 */
    private val contentSurfaces = WeakHashMap<Activity, String>()

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        track(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity?.get() === activity) resumedActivity = null
    }

    fun onScreenChanged(activity: Activity) {
        // 还没 resume 的（MainActivity.onCreate 里 setCurrentItem 触发的 onPageSelected）等 resume 时一起记。
        if (resumedActivity?.get() !== activity) return
        track(activity)
    }

    private fun track(activity: Activity) {
        val name = screenNameOf(activity)
        if (lastActivity?.get() === activity && name == currentScreen) return
        lastActivity = WeakReference(activity)
        val cameFrom = surface
        currentScreen = name
        surface = if (name in CONTENT_SCREENS) {
            contentSurfaces[activity] ?: cameFrom?.also { contentSurfaces[activity] = it }
        } else {
            name
        }
        analytics.logScreenView(name, activity.javaClass.simpleName, cameFrom)
    }

    private fun screenNameOf(activity: Activity): String {
        if (activity is AnalyticsScreen) {
            runCatching { activity.analyticsScreenName() }.getOrNull()?.let { return it }
        }
        return ACTIVITY_SCREENS[activity.javaClass] ?: defaultScreenName(activity.javaClass.simpleName)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    internal companion object {

        private val ACTIVITY_SCREENS: Map<Class<out Activity>, String> = mapOf(
            VActivity::class.java to "illust_detail",
            ImageDetailActivity::class.java to "image_viewer",
            SearchActivity::class.java to "search_results",
            UActivity::class.java to "user_profile",
            // 新旧个人页是同一个逻辑页面，screen_class 能区分版本。
            UserActivityV3::class.java to "user_profile",
            RankActivity::class.java to "ranking",
            // 外部链接 / 通知 / 分享的落地中转页：经它打开的内容 surface 就是 deep_link。
            OutWakeActivity::class.java to "deep_link",
            OutReversActivity::class.java to "share_receiver",
        )

        /** 看内容的页面：不改写 [surface]。 */
        private val CONTENT_SCREENS: Set<String> = buildSet {
            add("illust_detail")
            add("image_viewer")
            listOf(
                TemplateRoute.IMAGE_DETAIL,
                TemplateRoute.NOVEL_DETAIL,
                TemplateRoute.NOVEL_READER,
                TemplateRoute.COMIC_READER,
                TemplateRoute.COMMENTS,
                TemplateRoute.ILLUST_LIKERS,
                TemplateRoute.NOVEL_LIKERS,
                TemplateRoute.USER_INFO,
                TemplateRoute.UPSCALE_COMPARE,
                TemplateRoute.AI_UPSCALE,
                TemplateRoute.REMBG_HIGHLIGHT,
                TemplateRoute.REMBG_PREVIEW,
                TemplateRoute.UGOIRA_FRAMES,
                TemplateRoute.IMAGE_FILTER,
                TemplateRoute.FLAG_REASON,
                TemplateRoute.FLAG_DESC,
                TemplateRoute.PLAZA_OPEN_ILLUST,
                TemplateRoute.SNAPSHOT_VIEW,
                TemplateRoute.SNAPSHOT_VIEW_CLASSIC,
                TemplateRoute.SNAPSHOT_COMMENTS,
            ).mapTo(this) { it.screenName }
        }

        /**
         * 表里没有的 Activity（应用锁、裁图、第三方库的页面）：类名去掉 Activity 后缀转 snake_case，
         * `AppLockActivity` → `app_lock`。manifest 里声明的 Activity 类名不会被 R8 混淆。
         */
        fun defaultScreenName(simpleName: String): String =
            simpleName.removeSuffix("Activity")
                .ifEmpty { simpleName }
                .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .lowercase(Locale.ROOT)
    }
}
