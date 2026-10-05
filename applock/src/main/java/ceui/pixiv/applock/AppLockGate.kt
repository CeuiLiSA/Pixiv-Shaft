package ceui.pixiv.applock

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 把 [AppLockSession] 接到真实的生命周期上。
 *
 * - 进程前后台 → ProcessLifecycleOwner（[onStart] / [onStop]）。
 * - 锁定时任何一个宿主 Activity resume → 在它所在的 task 顶上启动 [AppLockActivity]。
 *   在 resume 而不是 start 时检查：ProcessLifecycleOwner 的 ON_START 晚于 Activity 的
 *   onActivityStarted 回调派发，resume 时会话状态才是最新的。
 * - 开着应用锁时，Android 13+ 关掉最近任务里的页面截图（setRecentsScreenshotEnabled）。
 *   不用 FLAG_SECURE：它会连用户自己截图、录屏一起禁掉。
 */
internal class AppLockGate(
    private val store: AppLockStore,
    private val session: AppLockSession,
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    /** 已 start 的 Activity，开关切换时立刻刷新它们的最近任务截图策略。 */
    private val startedActivities = LinkedHashSet<Activity>()

    /**
     * 还活着的锁屏页。锁定期间从通知 / deep link 打开的页面会再叠一张锁屏页，
     * 解锁时要一起 finish，否则返回时旧的那张会闪一下。
     */
    private val lockScreens = LinkedHashSet<AppLockActivity>()

    override fun onStart(owner: LifecycleOwner) {
        session.onAppForeground(store.isEnabled, store.relockTimeout.millis)
    }

    override fun onStop(owner: LifecycleOwner) {
        session.onAppBackground()
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities.add(activity)
        applyRecentsPolicy(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is AppLockActivity || activity.isFinishing || !session.isLocked) return
        if (!store.isEnabled || Authenticators.availability(activity) != AppLockAvailability.AVAILABLE) {
            // 屏幕锁被移除（移除本身要先过一次屏幕锁）后不再拦：否则用户会被永久锁在门外。
            unlock()
            return
        }
        activity.startActivity(AppLockActivity.intent(activity))
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities.remove(activity)
    }

    fun unlock() {
        session.unlock()
        lockScreens.toList().forEach(Activity::finish)
    }

    fun onEnabledChanged() {
        startedActivities.forEach(::applyRecentsPolicy)
    }

    private fun applyRecentsPolicy(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!store.isEnabled)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is AppLockActivity) lockScreens.add(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is AppLockActivity) lockScreens.remove(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
