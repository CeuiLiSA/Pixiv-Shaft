package ceui.pixiv.applock

/**
 * 本进程的锁定状态机。纯逻辑、不碰 Android，单测见 AppLockSessionTest。
 *
 * - 冷启动：开着应用锁就从锁定开始（[start]）。
 * - 离开 App（ProcessLifecycleOwner ON_STOP）记下时刻；回来（ON_START）时离开够久就重新锁定。
 * - 验证进行中（[isAuthenticating]）的前后台切换不算离开：Android 9 及以下用屏幕锁验证时，
 *   系统会把确认页盖在锁屏页上，进程会走一遍 ON_STOP → ON_START，那不是用户离开。
 *
 * [episode] 每次进入锁定换一个新值。锁屏页只对「没弹过的 episode」自动弹系统验证，
 * 用户取消后不会被同一个 episode 反复追着弹，回到前台的新 episode 才会再自动弹。
 * 取值来自开机后单调时钟，进程被杀重建后也不会与旧 Activity 存档里的值相撞。
 */
internal class AppLockSession(private val nowNanos: () -> Long) {

    var isLocked: Boolean = false
        private set

    var episode: Long = 0L
        private set

    var isAuthenticating: Boolean = false

    private var backgroundedAtNanos: Long? = null

    fun start(enabled: Boolean) {
        if (enabled) lock()
    }

    fun onAppBackground() {
        if (isAuthenticating) return
        backgroundedAtNanos = nowNanos()
    }

    fun onAppForeground(enabled: Boolean, timeoutMillis: Long) {
        val since = backgroundedAtNanos ?: return
        backgroundedAtNanos = null
        if (!enabled) return
        val awayMillis = (nowNanos() - since) / NANOS_PER_MILLI
        if (isLocked || awayMillis >= timeoutMillis) lock()
    }

    fun unlock() {
        isLocked = false
        isAuthenticating = false
        backgroundedAtNanos = null
    }

    private fun lock() {
        isLocked = true
        episode = maxOf(episode + 1, nowNanos())
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
