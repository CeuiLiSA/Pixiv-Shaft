package ceui.pixiv.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.tencent.mmkv.MMKV

/**
 * 「Chrome Custom Tabs」设置项的**设备本地**存储与判定。
 *
 * 为什么不放 [ceui.lisa.utils.Settings]：
 * 1. 本设置项直接依赖当前设备是否安装 Chrome，不具备跨设备性；
 * 2. 避免随设置导出（Shaft-Backup.json）、MoonSync 云端或系统云备份漂移到没有 Chrome 的设备上；
 * 3. 遵循仓库已有先例（[ceui.pixiv.ui.interpolate.RifePrefs]、[ceui.pixiv.ui.home.HomeR18Prefs]、
 *    [ceui.pixiv.ui.settings.TagLegibilityPrefs]），走设备本地独立 MMKV namespace。
 *
 * 状态机规则：
 * - 默认开（用户从未手动干预过，[isUserDisabled] 为 false）；
 * - 若未发现 Chrome，自动禁用（开关显示为关且不可交互，小字提示「未发现Chrome，已自动禁用」）；
 * - 处于自动禁用但用户新安装了 Chrome，自动启用（因为 [isUserDisabled] 仍为 false，重新检测到 Chrome 即生效）；
 * - 若用户主动在设置中关闭，写入 [isUserDisabled] = true；此后无论是否冷启动，均不再自动重新启用，
 *   并且打开链接回退至旧版行为；
 * - 该选项不进备份和云端。
 */
object ChromeTabsPrefs {

    const val MMKV_ID = "chrome_tabs_prefs_v1"
    private const val KEY_USER_DISABLED = "cct_user_disabled"
    const val CHROME_PACKAGE = "com.android.chrome"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    /**
     * 检测设备上是否安装并启用了 Chrome。
     */
    @JvmStatic
    fun isChromeInstalled(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(CHROME_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(CHROME_PACKAGE, 0)
            }
            info.applicationInfo?.enabled == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 用户是否手动关闭了该选项。默认 false（默认开）。
     */
    @JvmStatic
    fun isUserDisabled(): Boolean = store.decodeBool(KEY_USER_DISABLED, false)

    /**
     * 记录用户手动开关的状态。
     */
    @JvmStatic
    fun setUserDisabled(disabled: Boolean) {
        store.encode(KEY_USER_DISABLED, disabled)
    }

    /**
     * 判定当前是否应该使用 Chrome Custom Tabs：
     * 用户未手动关闭 + 设备上已安装并启用了 Chrome。
     */
    @JvmStatic
    fun shouldUseChromeCustomTabs(context: Context?): Boolean {
        if (context == null) return false
        return !isUserDisabled() && isChromeInstalled(context)
    }
}
