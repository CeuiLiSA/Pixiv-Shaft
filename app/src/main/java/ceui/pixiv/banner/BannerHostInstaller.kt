package ceui.pixiv.banner

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber
import java.util.WeakHashMap

/**
 * Installs a [BannerHost] overlay onto every [BannerHostOwner] Activity.
 *
 * **Timing.** API 29 added `onActivityPostCreated`, which fires *after* the
 * subclass's `onCreate` has fully run (so `setContentView` and any theme
 * switch are done). On API 24-28 we use `onActivityCreated`, which the
 * framework dispatches from *inside* `Activity.onCreate` — i.e. from
 * `super.onCreate(...)` of the subclass, **before** `setContentView` and
 * before BaseActivity's `updateTheme()` have run. Touching
 * `findViewById(android.R.id.content)` at that point forces AppCompat to
 * inflate its subDecor on whatever (potentially non-AppCompat) launch theme
 * the activity is still on and crashes with "You need to use a Theme.AppCompat
 * theme". We therefore post the install to the main looper from
 * `onActivityCreated`, which guarantees the runnable executes *after* the
 * full launch dispatch (`onCreate` + `onStart` + `onResume`) returns control
 * to the looper.
 */
class BannerHostInstaller(
    private val manager: BannerManager,
) : Application.ActivityLifecycleCallbacks {

    private val presenters = WeakHashMap<Activity, BannerPresenter>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // API 29+ has a dedicated post-create callback that fires after the
        // subclass's onCreate — use it directly and skip the post.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        scheduleInstall(activity)
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        installIfNeeded(activity)
    }

    /**
     * 给「注册得比第一个 Activity 还晚」的场景补一次安装。
     *
     * banner 系统整体是启动延迟批的一员（见 [ceui.lisa.activities.Shaft] 的
     * `runDeferredInit`），跑到的时候首个 Activity 早已 created / resumed 完，两个
     * 生命周期回调都错过了它 —— 不补这一次，最重要的首屏在整个会话里都不会有
     * banner 宿主。[installIfNeeded] 自带 presenters 去重，重复调用无害。
     *
     * 必须在主线程调用（会 addView）。
     */
    fun installNow(activity: Activity) {
        installIfNeeded(activity)
    }

    private fun scheduleInstall(activity: Activity) {
        if (activity !is BannerHostOwner) return
        mainHandler.post {
            // Activity may have been destroyed before the post runs (fast
            // back-out, dialog finish, etc.). Guard so we don't operate on
            // a dead window.
            if (activity.isFinishing) return@post
            if (activity is LifecycleOwner &&
                activity.lifecycle.currentState == Lifecycle.State.DESTROYED) return@post
            installIfNeeded(activity)
        }
    }

    private fun installIfNeeded(activity: Activity) {
        if (activity !is BannerHostOwner) return
        if (presenters.containsKey(activity)) return
        if (activity !is LifecycleOwner) {
            Timber.tag(TAG).e(
                "Activity %s implements BannerHostOwner but not LifecycleOwner; banner host will not be installed.",
                activity.javaClass.simpleName,
            )
            return
        }

        val content = try {
            activity.findViewById<ViewGroup>(android.R.id.content)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "findViewById(content) failed on %s", activity.javaClass.simpleName)
            return
        }
        if (content == null) {
            Timber.tag(TAG).e("android.R.id.content not found on %s", activity.javaClass.simpleName)
            return
        }

        val host = BannerHost(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP }
            elevation = 16f * activity.resources.displayMetrics.density
        }
        content.addView(host)

        val presenter = BannerPresenter(host, manager, activity.lifecycle)
        presenter.attach()
        presenters[activity] = presenter
        Timber.tag(TAG).d("Installed BannerHost on %s", activity.javaClass.simpleName)
    }

    override fun onActivityDestroyed(activity: Activity) {
        presenters.remove(activity)?.detach()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        private const val TAG = "BannerInstaller"
    }
}
