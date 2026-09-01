package ceui.pixiv.banner.host

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.banner.BannerEvent
import ceui.pixiv.banner.BannerHostInstaller
import ceui.pixiv.banner.BannerIconLoader
import ceui.pixiv.banner.BannerManager
import ceui.pixiv.banner.BannerViewBinder
import ceui.pixiv.banner.DefaultBannerViewBinder
import ceui.pixiv.banner.RealBannerManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * Process-wide entry point for the in-app banner system.
 *
 * Constructs the [BannerManager] with the default text-card binder, registers
 * a [BannerHostInstaller] on the [Application] (so every
 * [BannerHostOwner] activity gets a host overlay automatically), and starts
 * the WS → banner bridge so chat msg frames surface as banners.
 *
 * Call [bootstrap] once from `Application.onCreate` *after*
 * `ShaftChatGateway.bootstrap` — the WS bridge subscribes to the gateway's
 * `incoming` flow and that flow is only safe to touch after bootstrap.
 */
object InAppBanners {

    private const val TAG = "InAppBanners"
    private const val SCHEME = "shaft"
    private const val HOST_CHAT = "chat"

    /** 收藏库（本地镜像浏览页）。`shaft://bookmark-library?restrict=public|private`。 */
    private const val HOST_BOOKMARK_LIBRARY = "bookmark-library"

    private val bootstrapped = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val foreground = ForegroundTracker()

    lateinit var manager: BannerManager
        private set

    /**
     * Currently-resumed Activity, or `null` if no Activity is in the foreground.
     * Exposed for the WS → banner bridge to suppress banners while the user is
     * already viewing the source room.
     */
    fun currentActivity(): Activity? = foreground.current()

    /**
     * @param existingActivity bootstrap 发生时已经存在的前台 Activity（启动延迟批的情形）。
     *   下面两个 lifecycle callback 是现在才注册的，它的 created / resumed 早已发生过，
     *   不在这里补一次，首屏整场会话都不会有 banner 宿主。传 `null` 表示还没有 Activity。
     *   必须在主线程调用。
     */
    @JvmOverloads
    fun bootstrap(app: Application, existingActivity: Activity? = null) {
        if (!bootstrapped.compareAndSet(false, true)) return

        val binders = mapOf<String, BannerViewBinder>(
            BannerViewBinder.DEFAULT_KEY to DefaultBannerViewBinder(iconLoader = GlideBannerIconLoader),
        )
        manager = RealBannerManager(binders = binders)
        manager.start()

        val installer = BannerHostInstaller(manager)
        app.registerActivityLifecycleCallbacks(installer)
        app.registerActivityLifecycleCallbacks(foreground)

        existingActivity?.let {
            foreground.seed(it)
            installer.installNow(it)
        }

        ChatBannerBridge(app, manager, scope).start()

        scope.launch {
            manager.events
                .filterIsInstance<BannerEvent.Tapped>()
                .collect { handleTap(app, it.deepLink) }
        }

        Timber.tag(TAG).i("InAppBanners bootstrap complete")
    }

    private suspend fun handleTap(app: Application, deepLink: String?) {
        deepLink ?: return
        val uri = runCatching { Uri.parse(deepLink) }.getOrNull() ?: return
        if (uri.scheme != SCHEME) return
        when (uri.host) {
            HOST_CHAT -> openChat(app, uri)
            HOST_BOOKMARK_LIBRARY -> openBookmarkLibrary(app, uri)
            else -> Unit
        }
    }

    /**
     * `shaft://bookmark-library?restrict=public|private&type=0|1` → 收藏库，
     * 落在指定的那个书架上（type：0=插画 1=小说，见 MirrorContentType.code）。
     */
    private suspend fun openBookmarkLibrary(app: Application, uri: Uri) {
        val activity = foreground.current()
        val ctx: Context = activity ?: app
        val intent = Intent(ctx, TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.BOOKMARK_LIBRARY.key)
            uri.getQueryParameter("restrict")?.let {
                putExtra(ceui.lisa.utils.Params.STAR_TYPE, it)
            }
            uri.getQueryParameter("type")?.toIntOrNull()?.let {
                putExtra(ceui.pixiv.ui.library.BookmarkLibraryUi.ARG_CONTENT_TYPE, it)
            }
            if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) {
            try {
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Failed to launch bookmark library from banner tap")
            }
        }
    }

    private suspend fun openChat(app: Application, uri: Uri) {
        val activity = foreground.current()
        val ctx: Context = activity ?: app
        val peer = uri.getQueryParameter("peer")?.toLongOrNull() ?: 0L
        val intent = Intent(ctx, TemplateActivity::class.java).apply {
            if (peer > 0L) {
                // 1v1: open the per-conversation chat fragment directly,
                // not the list — the user explicitly wants this thread.
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CHAT.key)
                putExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, peer)
            } else {
                // Global: dispatch through the dedicated "open global" case
                // so we don't bounce to the new conversation list (which
                // now owns the bare "聊天室" route without a peer).
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CHAT_GLOBAL_ROOM.key)
            }
            if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) {
            try {
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Failed to launch chat from banner tap")
            }
        }
    }

    /**
     * 宿主侧的 [BannerIcon.Url] 加载器：走 Glide + [GlideUrlChild]（pximg 需要 referer），
     * 圆形裁切，占位用 Shaft 自己的 logo（与 [ChatBannerBridge] 的匿名 / 失败兜底一致；
     * 不用 chat_avatar_placeholder —— 它依赖 Material 的 colorSurfaceContainerHigh，
     * AppCompat 主题的 MainActivity 上解析不了）。
     */
    private object GlideBannerIconLoader : BannerIconLoader {
        override fun load(target: ImageView, url: String) {
            runCatching {
                Glide.with(target)
                    .load(GlideUrlChild(url))
                    .placeholder(R.drawable.icon_shaft_with_bg)
                    .circleCrop()
                    .into(target)
            }.onFailure { Timber.tag(TAG).w(it, "banner icon load failed") }
        }
    }

    /**
     * Cheapest possible foreground-activity tracker. WeakReference so we never
     * keep an Activity alive past `onDestroy`, and we only care about which
     * Activity is currently `RESUMED` for the purpose of launching the next
     * Activity off of it.
     */
    private class ForegroundTracker : Application.ActivityLifecycleCallbacks {
        @Volatile
        private var ref: WeakReference<Activity>? = null

        fun current(): Activity? = ref?.get()

        /** 补记一个「注册前就已经 resumed」的 Activity，见 [bootstrap] 的 existingActivity。 */
        fun seed(activity: Activity) {
            ref = WeakReference(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            ref = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (ref?.get() === activity) ref = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
