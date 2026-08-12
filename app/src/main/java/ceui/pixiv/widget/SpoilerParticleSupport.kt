package ceui.pixiv.widget

import android.app.ActivityManager
import android.content.Context
import com.tencent.mmkv.MMKV
import timber.log.Timber

/**
 * Telegram 粒子渲染器（[ceui.pixiv.widget.telegram.SpoilerEffect2]）的能力闸门 + 崩溃熔断。
 *
 * 背景（v4.8.4 线上事故）：粒子走 OpenGL ES 3.0（transform feedback），部分老 GPU / 驱动在
 * EGL 初始化或首帧编译 shader 时直接 **native crash**——没接 crashlytics-ndk，Firebase 上
 * 一条日志都没有；而首页推荐是本地缓存优先、打码卡在 splash 底下就 bind，受影响用户表现为
 * 「点开图标秒闪退、无日志、无法自救」的死循环。上游 Telegram 靠调用侧 LiteMode 挡低端机，
 * 移植时这层门槛丢了。粒子是纯装饰，在任何设备上都不配换一次崩溃：不支持就只出模糊遮罩。
 *
 * 三层防御，调用侧统一收口在 `SpoilerEffect2.supports()`：
 * 1. **能力检查**：`reqGlEsVersion` >= 3.0 才放行，挡住 `eglChooseConfig` 根本拿不到
 *    ES3 config 的机器（Mali-400 之类只支持 ES2 的老 GPU，minSdk 24 覆盖得到）；
 * 2. **崩溃熔断**：渲染线程进 GL 危险窗口（EGL init + 首帧）前落盘 [PROBING_KEY]，活着
 *    出窗口就清掉。下次启动发现标记还在 = 上次进程死在窗口里（多半是驱动 native crash，
 *    Java 层拦不住），从此永久禁用粒子。窗口只有几十毫秒，恰好被 LMK 杀在窗口内误伤的
 *    概率可以忽略——而误伤的代价也只是少一层装饰；
 * 3. **Java 兜底**：渲染线程整体 try-catch（见 `SpoilerEffect2.SpoilerThread.run`），
 *    兜住的异常降级为本进程禁用，不落盘——下次启动还有全新的机会。
 */
object SpoilerParticleSupport {

    private const val DISABLED_KEY = "spoiler-particles-disabled"
    private const val PROBING_KEY = "spoiler-particles-gl-probing"
    private const val GLES_3_0 = 0x30000

    @Volatile
    private var cachedVerdict: Boolean? = null

    @JvmStatic
    fun isSupported(context: Context?): Boolean {
        cachedVerdict?.let { return it }
        if (context == null) {
            // 早于 AndroidUtilities.initialize 的调用：这次先不画，也不缓存结论
            return false
        }
        // 评估自己出任何岔子都按不支持处理：这里的一切都只为一层装饰服务
        val verdict = runCatching { evaluate(context) }.getOrDefault(false)
        cachedVerdict = verdict
        return verdict
    }

    private fun evaluate(context: Context): Boolean {
        val store = MMKV.defaultMMKV()
        if (store.getBoolean(DISABLED_KEY, false)) {
            return false
        }
        if (store.getBoolean(PROBING_KEY, false)) {
            Timber.w(
                "Spoiler particles disabled permanently: " +
                    "last GL probe never finished (likely a native driver crash)"
            )
            store.putBoolean(DISABLED_KEY, true)
            store.removeValueForKey(PROBING_KEY)
            return false
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val glEsVersion = am.deviceConfigurationInfo.reqGlEsVersion
        if (glEsVersion < GLES_3_0) {
            Timber.i("Spoiler particles disabled: OpenGL ES 0x%x < 3.0", glEsVersion)
            return false
        }
        return true
    }

    /** 渲染线程进入 GL 危险窗口（EGL init + 首帧）前调用。 */
    @JvmStatic
    fun onGlProbeStarted() {
        runCatching { MMKV.defaultMMKV().putBoolean(PROBING_KEY, true) }
    }

    /** 活着离开危险窗口（首帧画完 / init 优雅失败 / 异常被兜住）时调用，幂等。 */
    @JvmStatic
    fun onGlProbeFinished() {
        runCatching { MMKV.defaultMMKV().removeValueForKey(PROBING_KEY) }
    }

    /** 渲染线程兜住了 Throwable：本进程内不再尝试。 */
    @JvmStatic
    fun onRenderFailed(t: Throwable) {
        Timber.e(t, "Spoiler particle renderer failed; particles disabled for this process")
        cachedVerdict = false
    }
}
