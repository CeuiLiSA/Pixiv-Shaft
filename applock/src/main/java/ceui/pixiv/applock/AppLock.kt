package ceui.pixiv.applock

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * 应用锁（issue #1197）：离开 App 后再回来，需要先通过系统验证（指纹 / 面部 / 屏幕锁）。
 *
 * 默认关闭。验证全部交给 androidx.biometric 的系统弹窗，本模块不保存任何密码；
 * 「密码」就是手机自己的屏幕锁 PIN / 图案 / 密码。
 *
 * 宿主只需在 Application.onCreate 里、第一个 Activity 创建之前调用一次 [install]，
 * 设置页用 [AppLockEnabler] 开启（开启前要先验证一次，确认用户解得开），用 [disable] 关闭。
 */
public object AppLock {

    /** 宿主把自己当前的主题（含用户选的主题色）套到锁屏页上。在 super.onCreate 之前调用。 */
    public fun interface ThemeApplier {
        public fun applyTo(activity: Activity)
    }

    private var installed: Installed? = null

    private class Installed(
        val store: AppLockStore,
        val session: AppLockSession,
        val gate: AppLockGate,
        val themeApplier: ThemeApplier,
    )

    @JvmStatic
    public fun install(application: Application, themeApplier: ThemeApplier) {
        check(installed == null) { "AppLock.install called twice" }
        val store = AppLockStore()
        val session = AppLockSession(SystemClock::elapsedRealtimeNanos).apply { start(store.isEnabled) }
        val gate = AppLockGate(store, session)
        application.registerActivityLifecycleCallbacks(gate)
        ProcessLifecycleOwner.get().lifecycle.addObserver(gate)
        installed = Installed(store, session, gate, themeApplier)
    }

    @JvmStatic
    public val isEnabled: Boolean
        get() = requireInstalled().store.isEnabled

    @JvmStatic
    public var relockTimeout: RelockTimeout
        get() = requireInstalled().store.relockTimeout
        set(value) {
            requireInstalled().store.relockTimeout = value
        }

    @JvmStatic
    public fun availability(context: Context): AppLockAvailability = Authenticators.availability(context)

    /** 关闭不再要求验证：能走到设置页，说明这次已经解过锁。 */
    @JvmStatic
    public fun disable() {
        val installed = requireInstalled()
        installed.store.isEnabled = false
        installed.gate.unlock()
        installed.gate.onEnabledChanged()
    }

    /** 只由 [AppLockEnabler] 在验证成功后调用。当前会话视为已解锁，下次离开 App 才开始生效。 */
    internal fun enable() {
        val installed = requireInstalled()
        installed.store.isEnabled = true
        installed.gate.onEnabledChanged()
    }

    /** 验证通过：结束本次锁定，并关掉所有 task 里的锁屏页。 */
    internal fun unlock() {
        requireInstalled().gate.unlock()
    }

    internal val session: AppLockSession
        get() = requireInstalled().session

    internal val themeApplier: ThemeApplier
        get() = requireInstalled().themeApplier

    private fun requireInstalled(): Installed =
        checkNotNull(installed) { "AppLock.install must be called in Application.onCreate" }
}
