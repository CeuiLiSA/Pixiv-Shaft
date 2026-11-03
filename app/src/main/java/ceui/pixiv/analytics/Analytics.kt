package ceui.pixiv.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.lisa.helper.ThemeHelper
import ceui.lisa.utils.Params
import ceui.pixiv.i18n.AppLocales
import ceui.pixiv.login.PixivOAuthResult
import ceui.pixiv.session.SessionManager
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Analytics（GA4）产品埋点。事件字典、参数取值和 GA4 后台要注册的自定义维度见
 * `docs/analytics-tracking-plan.md`；加事件先改那份文档，再改这里。
 *
 * ## 三路埋点各管什么
 *
 * - 这里：**产品行为的形状**——哪个页面、从哪个入口来、做了什么、成没成功，按会话 / 人群聚合着看，
 *   回答「功能有没有人用、转化掉在哪一步」。
 * - [ceui.pixiv.events.EventReporter]：社区热度榜的原始数据，带作品 id。
 * - [ceui.pixiv.actions.Nana7miSearchTelemetry]：服务端代搜的逐请求链路，带搜索词，只发自家服务端。
 *
 * 口径向它们看齐：收藏 / 关注和 EventReporter 一样**在服务端确认之后**才记（由 [ceui.pixiv.actions.PixivActionQueue]
 * 调 [bookmarkConfirmed] / [followConfirmed]），终态失败另记 [Event.ACTION_FAIL]；入口页面在点击那一刻
 * 写进队列 payload，确认时原样带出来。失败原因的取值与 Nana7mi 遥测同一套（[AnalyticsClassifiers.failureType]），
 * 两边的报表能直接对照。
 *
 * ## 隐私红线
 *
 * 1. 不发任何身份：不调 setUserId，不带 pixiv uid，不带作品 / 小说 / 用户 id。GA4 的 app_instance_id
 *    配上内容 id 就能拼出单个人的完整浏览记录。
 * 2. 不发用户输入：搜索词、评论、标签原文一律不进参数，只记「搜了几个词」这类形状。
 * 3. 不发内容分级：R-18 / AI 标记不进参数。
 *
 * ## 工程约束
 *
 * - 参数值只用本文件里的 `wire` 字面量（单测锁住了 GA4 的长度 / 字符限制），不拼动态字符串——
 *   GA4 自定义维度有基数上限，超了全进 (other)。唯一例外是 `http_<状态码>`，取值有限。
 * - 永不抛出、永不阻塞调用方：埋点坏了不能拖垮一次收藏点击。
 * - 用户在「试验性设置」里关掉 Firebase 后，SDK 自己会丢事件；这里也提前短路，省掉拼 Bundle。
 * - 失败类事件有进程级配额（[budget]）：断网时一屏几十个请求同时失败，一次批量下载能连着失败几百页，
 *   不设上限会把真实分布淹掉。
 * - 进程级服务（见 [ceui.pixiv.services.ServicesProvider]）。分两步装配，不碰冷启动首帧：
 *   [install]（onCreate 同步段）只挂页面回调；[attach]（延迟批）才取 SDK 实例，之前记下的事件先攒着、
 *   到时一并补发。
 */
@Singleton
class Analytics @Inject constructor(
    @ApplicationContext app: Context,
) {

    private val app: Context = app

    /** 事件名。能对上 GA4 推荐事件的（login / search）用官方名，后台报表自带解读。 */
    object Event {
        const val ILLUST_VIEW = "illust_view"
        const val BOOKMARK = "bookmark"
        const val FOLLOW = "follow"
        const val ACTION_FAIL = "action_fail"
        const val DOWNLOAD = "download"
        const val DOWNLOAD_FAIL = "download_fail"
        const val SEARCH = FirebaseAnalytics.Event.SEARCH
        const val LOGIN_BEGIN = "login_begin"
        const val LOGIN = FirebaseAnalytics.Event.LOGIN
        const val LOGIN_FAIL = "login_fail"
        const val LOGOUT = "logout"
        const val LOAD_ERROR = "load_error"

        internal val ALL = listOf(
            ILLUST_VIEW, BOOKMARK, FOLLOW, ACTION_FAIL, DOWNLOAD, DOWNLOAD_FAIL, SEARCH,
            LOGIN_BEGIN, LOGIN, LOGIN_FAIL, LOGOUT, LOAD_ERROR,
        )
    }

    object Param {
        /** 内容是在哪个浏览页（推荐流 / 排行 / 搜索结果 / 作者主页…）被找到的，见 [ScreenTracker.surface]。 */
        const val SURFACE = "surface"
        const val CONTENT_TYPE = FirebaseAnalytics.Param.CONTENT_TYPE
        const val METHOD = FirebaseAnalytics.Param.METHOD
        const val ACTION = "action"
        const val NAV = "nav"
        const val VISIBILITY = "visibility"
        const val ITEM_COUNT = "item_count"
        const val SEARCH_TYPE = "search_type"
        const val QUERY_KIND = "query_kind"
        const val TRIGGER = "trigger"
        const val TERM_COUNT = "term_count"
        const val ACTION_TYPE = "action_type"
        const val REASON = "reason"

        internal val ALL = listOf(
            SURFACE, CONTENT_TYPE, METHOD, ACTION, NAV, VISIBILITY, ITEM_COUNT, SEARCH_TYPE,
            QUERY_KIND, TRIGGER, TERM_COUNT, ACTION_TYPE, REASON,
        )
    }

    /** 用户属性。GA4 上限 25 个，名字 ≤ 24 字符，值 ≤ 36 字符。 */
    object UserProp {
        const val LOGIN_STATE = "login_state"
        const val PIXIV_PREMIUM = "pixiv_premium"
        const val APP_CHANNEL = "app_channel"
        const val THEME_MODE = "theme_mode"
        const val DETAIL_UI = "detail_ui"
        const val APP_LOCALE = "app_locale"

        internal val ALL = listOf(LOGIN_STATE, PIXIV_PREMIUM, APP_CHANNEL, THEME_MODE, DETAIL_UI, APP_LOCALE)
    }

    enum class ContentType(val wire: String) {
        ILLUST("illust"),
        MANGA("manga"),
        UGOIRA("ugoira"),
        NOVEL("novel"),
        /** 批量入队：插画 / 漫画 / 动图混在一起。 */
        MIXED("mixed"),
    }

    enum class BookmarkMethod(val wire: String) {
        SINGLE("single"),
        WITH_TAGS("with_tags"),
        BULK("bulk"),
    }

    enum class FollowMethod(val wire: String) {
        MANUAL("manual"),
        /** 设置里的「收藏后自动关注作者」。 */
        AUTO_AFTER_BOOKMARK("auto_after_bookmark"),
    }

    enum class DownloadMethod(val wire: String) {
        /** 详情页 / 卡片上点的下载，单页或整部。 */
        DIRECT("direct"),
        /** 作者全部作品 / 收藏夹 / 批量选择进的下载队列。 */
        BULK("bulk"),
        /** 小说系列整部下载。 */
        SERIES("series"),
        /** 设置里的「收藏后自动下载」。 */
        AUTO_AFTER_BOOKMARK("auto_after_bookmark"),
    }

    enum class LoginMethod(val wire: String) {
        /** 浏览器 OAuth（回调落在 OutWakeActivity，登录 / 注册两个入口共用这条回调）。 */
        OAUTH("oauth"),
        /** 浏览器 OAuth 的注册入口；只出现在 login_begin 上，回调分不出是哪个入口发起的。 */
        SIGNUP("signup"),
        REFRESH_TOKEN("refresh_token"),
        /** 从剪贴板导入账号 JSON。 */
        IMPORT("import"),
    }

    enum class LogoutReason(val wire: String) {
        USER("user"),
        /** refresh_token 失效被踢下线（TokenInterceptor）。 */
        TOKEN_INVALID("token_invalid"),
    }

    enum class SearchType(val wire: String) {
        ILLUST("illust"),
        NOVEL("novel"),
        USER("user"),
    }

    enum class QueryKind(val wire: String) {
        KEYWORD("keyword"),
        /** 粘贴了 pixiv 链接，直接跳转。 */
        URL("url"),
        /** 纯数字，按作品 id / 用户 id 直达。 */
        NUMERIC_ID("numeric_id"),
    }

    enum class SearchTrigger(val wire: String) {
        /** 带着关键词进搜索页（点标签、热门标签、发现页、深链…），搜索是导航的结果。 */
        NAVIGATION("navigation"),
        /** 在搜索页里自己发起：输入回车、点补全、改 chip、换筛选。 */
        IN_PAGE("in_page"),
    }

    /** [attach] 之后才有。 */
    @Volatile
    private var firebase: FirebaseAnalytics? = null

    /** SDK 取不到（[attach] 失败），之后的事件一律丢弃。 */
    @Volatile
    private var unavailable = false
    private val installed = AtomicBoolean(false)

    /** [attach] 之前记下的事件（冷启动首屏的 screen_view 就在这里等），读写都要持锁。 */
    private val pending = ArrayList<Pair<String, Bundle>>()
    private val screens = ScreenTracker(this)
    private val budgets = ConcurrentHashMap<String, AtomicInteger>()

    /** 已经记过的加载失败。同一个异常对象在错误态反复渲染时会被映射很多次，只记第一次。 */
    private val seenLoadErrors: MutableSet<Throwable> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    // ── 装配 ──────────────────────────────────────────────────────────────────

    /**
     * 必须在第一个 Activity resume 之前调用（[Shaft.onCreate] 同步段）：页面浏览改成了手动上报
     * （manifest 里关掉了 SDK 的自动 screen_view），晚注册就丢掉冷启动的首屏。
     *
     * 只挂一个 lifecycle callback，**不碰 SDK**：FirebaseAnalytics 的初始化是 0d65cf7b3 实测后
     * 特意挪出首帧的那一批之一，取实例放在 [attach]。幂等。
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        (app as Application).registerActivityLifecycleCallbacks(screens)
    }

    /**
     * 延迟批里调（[Shaft] 的 runDeferredInit，排在 setAnalyticsCollectionEnabled 之后）：取 SDK
     * 实例，按原顺序补发此前攒下的事件，再同步用户属性。幂等。
     *
     * 补发的事件时间戳是补发那一刻（SDK 不收自定义时间），离真实时间差首帧到空闲的那一小段。
     */
    fun attach() {
        if (firebase != null || unavailable) return
        val instance = try {
            FirebaseAnalytics.getInstance(app)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "FirebaseAnalytics unavailable, analytics disabled")
            synchronized(pending) {
                unavailable = true
                pending.clear()
            }
            return
        }
        // 补发和置位在同一把锁里：别的线程此刻要记的事件会等补发完，再直接发，顺序不乱。
        synchronized(pending) {
            pending.forEach { (event, params) -> send(instance, event, params) }
            pending.clear()
            firebase = instance
        }
        syncUserProperties()
    }

    /**
     * 同步用户属性（要读设置、会话和 MMKV 里的语言，首帧用不到），由 [attach] 调一次。
     * 主题 / 详情页样式这类设置改了要到下次冷启动才更新，对按人群切分足够了。
     */
    private fun syncUserProperties() {
        val settings = Shaft.sSettings ?: return
        setUserProperty(UserProp.APP_CHANNEL, BuildConfig.UPDATE_CHANNEL)
        setUserProperty(
            UserProp.THEME_MODE,
            when (settings.themeType) {
                ThemeHelper.ThemeType.LIGHT_MODE -> "light"
                ThemeHelper.ThemeType.DARK_MODE -> "dark"
                else -> "system"
            },
        )
        setUserProperty(UserProp.DETAIL_UI, if (settings.isUseArtworkV3) "v3" else "classic")
        setUserProperty(UserProp.APP_LOCALE, runCatching { AppLocales.savedTag() }.getOrNull() ?: "system")
        // 登录 / 切号 / 退登都会推一次；premium 要等账号数据回来才知道，未知时清掉而不是猜。
        SessionManager.loggedInAccount.observeForever { account ->
            setUserProperty(UserProp.LOGIN_STATE, if (account?.access_token != null) "logged_in" else "guest")
            setUserProperty(UserProp.PIXIV_PREMIUM, account?.user?.is_premium?.toString())
        }
    }

    // ── 页面 ──────────────────────────────────────────────────────────────────

    /**
     * 当前浏览页，入队类动作在点击那一刻取它写进 payload（见 [bookmarkConfirmed]）。
     * 主线程以外也能读。
     */
    val currentSurface: String?
        get() = screens.surface

    /**
     * 承载多个逻辑页面的 Activity（首页底栏切 tab 等）在页面变了之后调用，见 [AnalyticsScreen]。
     * Activity 不在前台时什么也不做，等它 resume 时自然会记。
     */
    fun screenChanged(activity: Activity) {
        screens.onScreenChanged(activity)
    }

    internal fun logScreenView(screenName: String, screenClass: String, cameFrom: String?) {
        // Crashlytics 的自定义键只随崩溃报告上传：崩在哪一页是排查最先要的线索。
        runCatching { FirebaseCrashlytics.getInstance().setCustomKey("screen", screenName) }
        if (!isEnabled()) return
        dispatch(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle(3).apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
            if (cameFrom != null) putString(Param.SURFACE, cameFrom)
        })
    }

    // ── 内容消费 ──────────────────────────────────────────────────────────────

    /**
     * 插画详情翻到了一幅作品。
     *
     * @param illustType pixiv 的 `Illust.type`。
     * @param swipe false = 从列表点进来的第一幅，true = 在详情里左右滑到的；两者之比就是详情页的连刷深度。
     */
    fun illustView(illustType: String?, swipe: Boolean) {
        log(Event.ILLUST_VIEW) {
            putString(Param.CONTENT_TYPE, AnalyticsClassifiers.contentTypeOf(illustType).wire)
            putString(Param.NAV, if (swipe) "swipe" else "open")
        }
    }

    // ── 收藏 / 关注（PixivActionQueue 在服务端确认后调）────────────────────────

    /**
     * @param surface 入队时的 [currentSurface]，随 payload 持久化；老版本入队的行为 null，就不带。
     * @param method  入队时的 [BookmarkMethod.wire]，同上。
     */
    fun bookmarkConfirmed(
        contentType: ContentType,
        add: Boolean,
        restrict: String?,
        method: String?,
        surface: String?,
    ) {
        log(Event.BOOKMARK, surface) {
            putString(Param.CONTENT_TYPE, contentType.wire)
            putString(Param.ACTION, if (add) "add" else "remove")
            if (add) putString(Param.VISIBILITY, visibilityOf(restrict))
            if (method != null) putString(Param.METHOD, method)
        }
    }

    /**
     * 参数口径同 [bookmarkConfirmed]。公开 / 悄悄关注之间的切换不是新关注，和 EventReporter
     * 一样不记。
     */
    fun followConfirmed(follow: Boolean, restrict: String?, method: String?, surface: String?) {
        log(Event.FOLLOW, surface) {
            putString(Param.ACTION, if (follow) "add" else "remove")
            if (follow) putString(Param.VISIBILITY, visibilityOf(restrict))
            if (method != null) putString(Param.METHOD, method)
        }
    }

    /**
     * 收藏 / 关注在队列里打满重试后终态失败、界面被回滚（被用户新一次点击覆盖的不算）。
     * [Event.BOOKMARK] / [Event.FOLLOW] 已经是净成功量，这条单看失败面。
     * 进程级配额 [ACTION_FAIL_BUDGET]：一次批量收藏能有几千项，碰上限流打满重试会连着失败一大串。
     */
    fun actionFailed(actionType: String, cause: Throwable?, surface: String?) {
        if (!budget(Event.ACTION_FAIL, ACTION_FAIL_BUDGET)) return
        log(Event.ACTION_FAIL, surface) {
            putString(Param.ACTION_TYPE, actionType)
            putString(Param.REASON, cause?.let(AnalyticsClassifiers::failureType) ?: AnalyticsClassifiers.UNEXPECTED)
        }
    }

    // ── 下载 ──────────────────────────────────────────────────────────────────
    //
    // 下载记的是**请求**：它没有「服务端真值」可等，用户点了就是一次产品行为。结果面只看失败
    // （download_fail，按页记）；成功量 ≈ 请求页数 − 失败页数。不按页记成功：一次作者全部作品
    // 的批量下载能有上万页，逐页报会让这一个人的数据淹掉所有人。

    /** 插画 / 漫画 / 动图进了下载队列。[pages] 是这次入队的页数。 */
    fun illustDownload(illustType: String?, pages: Int, method: DownloadMethod) {
        download(AnalyticsClassifiers.contentTypeOf(illustType), pages, method)
    }

    fun novelDownload(novels: Int, method: DownloadMethod) {
        download(ContentType.NOVEL, novels, method)
    }

    /** 批量入队：[works] 是作品数而不是页数（页数要等消费者展开才知道）。 */
    fun bulkDownload(works: Int) {
        download(ContentType.MIXED, works, DownloadMethod.BULK)
    }

    private fun download(contentType: ContentType, count: Int, method: DownloadMethod) {
        if (count <= 0) return
        log(Event.DOWNLOAD) {
            putString(Param.CONTENT_TYPE, contentType.wire)
            putString(Param.METHOD, method.wire)
            putLong(Param.ITEM_COUNT, count.toLong())
        }
    }

    /** 插画单页下载失败（网络 / HTTP / 存储）。进程级配额 [DOWNLOAD_FAIL_BUDGET]。 */
    fun illustDownloadFailed(error: Throwable?) {
        downloadFailed(ContentType.ILLUST, AnalyticsClassifiers.downloadFailureReason(error))
    }

    /** 存储层（MediaStore / SAF）建不出目标文件，还没开始传。 */
    fun illustDownloadStorageFailed() {
        downloadFailed(ContentType.ILLUST, AnalyticsClassifiers.STORAGE_UNAVAILABLE)
    }

    /** 小说下载队列里一篇终态失败。 */
    fun novelDownloadFailed(cause: Throwable?) {
        downloadFailed(ContentType.NOVEL, cause?.let(AnalyticsClassifiers::downloadFailureReason) ?: AnalyticsClassifiers.UNEXPECTED)
    }

    private fun downloadFailed(contentType: ContentType, reason: String) {
        if (!budget(Event.DOWNLOAD_FAIL, DOWNLOAD_FAIL_BUDGET)) return
        log(Event.DOWNLOAD_FAIL) {
            // 插画管线拿不到作品类型（失败回调只有 DownloadItem），漫画 / 动图也归在 illust。
            putString(Param.CONTENT_TYPE, contentType.wire)
            putString(Param.REASON, reason)
        }
    }

    // ── 搜索 ──────────────────────────────────────────────────────────────────

    /**
     * 发起了一次搜索。**不带搜索词**（见类注释），只记它的形状。
     *
     * @param termCount 关键词按空白拆开后的个数（chip 数），反映是单标签浏览还是组合筛选。
     */
    fun search(type: SearchType, kind: QueryKind, trigger: SearchTrigger, termCount: Int) {
        log(Event.SEARCH) {
            putString(Param.SEARCH_TYPE, type.wire)
            putString(Param.QUERY_KIND, kind.wire)
            putString(Param.TRIGGER, trigger.wire)
            putLong(Param.TERM_COUNT, termCount.toLong())
        }
    }

    // ── 账号 ──────────────────────────────────────────────────────────────────

    fun loginBegin(method: LoginMethod) {
        log(Event.LOGIN_BEGIN) { putString(Param.METHOD, method.wire) }
    }

    fun login(method: LoginMethod) {
        log(Event.LOGIN) { putString(Param.METHOD, method.wire) }
    }

    fun loginFailed(method: LoginMethod, failure: PixivOAuthResult.Failure) {
        loginFailed(method, AnalyticsClassifiers.loginFailureReason(failure))
    }

    /** 换 token 的调用本身抛了（不是 [PixivOAuthResult.Failure] 那种有结论的失败）。 */
    fun loginError(method: LoginMethod, error: Throwable) {
        loginFailed(method, AnalyticsClassifiers.failureType(error))
    }

    /** 拿到了账号数据但解析不出 / 落不了库。 */
    fun loginPersistFailed(method: LoginMethod) {
        loginFailed(method, "persist_failed")
    }

    private fun loginFailed(method: LoginMethod, reason: String) {
        log(Event.LOGIN_FAIL) {
            putString(Param.METHOD, method.wire)
            putString(Param.REASON, reason)
        }
    }

    /**
     * 退出登录。被踢下线（[LogoutReason.TOKEN_INVALID]）每个进程只记一次：失效时并发中的
     * 每个请求都会各自走一遍踢人逻辑。
     */
    fun logout(reason: LogoutReason) {
        if (reason == LogoutReason.TOKEN_INVALID && !budget(LOGOUT_TOKEN_INVALID_KEY, 1)) return
        log(Event.LOGOUT) { putString(Param.REASON, reason.wire) }
    }

    // ── 错误 ──────────────────────────────────────────────────────────────────

    /**
     * 列表加载失败、错误文案展示给了用户（整屏错误态或刷新失败的轻提示）。所在页面由 GA4
     * 自动挂在事件上，不用另带。同一个异常对象只记一次，进程级配额 [LOAD_ERROR_BUDGET]。
     */
    fun loadError(error: Throwable) {
        if (!seenLoadErrors.add(error)) return
        if (!budget(Event.LOAD_ERROR, LOAD_ERROR_BUDGET)) return
        log(Event.LOAD_ERROR) { putString(Param.REASON, AnalyticsClassifiers.failureType(error)) }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private fun visibilityOf(restrict: String?): String =
        if (restrict == Params.TYPE_PRIVATE) "private" else "public"

    private fun isEnabled(): Boolean {
        if (unavailable) return false
        // sSettings 在极早期可能还没读出来：读不到时交给 SDK 自己持久化的开关去挡。
        val settings = Shaft.sSettings
        return settings == null || settings.isFirebaseEnable
    }

    /** 这个进程里 [event] 还能不能再记一条。 */
    private fun budget(event: String, max: Int): Boolean =
        budgets.getOrPut(event) { AtomicInteger() }.incrementAndGet() <= max

    /** @param surface 默认取当前浏览页；异步确认的事件传入队时的快照（可为 null = 不带）。 */
    private inline fun log(event: String, surface: String? = screens.surface, build: Bundle.() -> Unit) {
        if (!isEnabled()) return
        val params = try {
            Bundle().apply(build)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "build params failed for %s", event)
            return
        }
        if (surface != null) params.putString(Param.SURFACE, surface)
        dispatch(event, params)
    }

    private fun dispatch(event: String, params: Bundle) {
        firebase?.let { return send(it, event, params) }
        synchronized(pending) {
            // 持锁再看一次：attach 可能刚补发完。
            firebase?.let { return send(it, event, params) }
            if (!unavailable && pending.size < PENDING_CAP) pending += event to params
        }
    }

    private fun send(instance: FirebaseAnalytics, event: String, params: Bundle) {
        try {
            instance.logEvent(event, params)
            if (BuildConfig.DEBUG) Timber.tag(TAG).d("%s %s", event, params)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "logEvent failed for %s", event)
        }
    }

    private fun setUserProperty(name: String, value: String?) {
        val instance = firebase ?: return
        try {
            instance.setUserProperty(name, value)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "setUserProperty failed for %s", name)
        }
    }

    private companion object {
        const val TAG = "Analytics"
        const val ACTION_FAIL_BUDGET = 30
        const val DOWNLOAD_FAIL_BUDGET = 30
        const val LOAD_ERROR_BUDGET = 50
        const val LOGOUT_TOKEN_INVALID_KEY = "logout:token_invalid"
        /** attach 之前最多攒多少条。正常只有冷启动首屏那一两条 screen_view。 */
        const val PENDING_CAP = 50
    }
}
