package ceui.pixiv.ui.home

import ceui.lisa.BuildConfig
import ceui.pixiv.session.SessionManager
import com.tencent.mmkv.MMKV

/**
 * 「首页推荐包含 R-18 作品」开关的**设备本地**存储。
 *
 * 为什么不放 [ceui.lisa.utils.Settings]：该开关跟当前设备上的「网页登录会话 (PHPSESSID)」强绑定，
 * 不具备跨设备性。若随 Settings 跨设备备份或通过 MoonSync 云同步，在新设备或还原设备上由于缺失
 * 网页 Cookie 会直接产生无效请求或被重置。
 *
 * 遵循仓库已有先例（[ceui.pixiv.ui.interpolate.RifePrefs]、[ceui.pixiv.ui.settings.TagLegibilityPrefs]）：
 * - 不进 [ceui.lisa.utils.Settings]，云同步与 Shaft-Backup.json 天然排除；
 * - 独立 MMKV 存储命名空间（home_r18_prefs_v1），并在 backup_rules.xml / data_extraction_rules.xml
 *   中排除，换机/系统还原后读到的就是默认的 false。
 */
object HomeR18Prefs {

    private const val MMKV_ID = "home_r18_prefs_v1"
    private const val KEY_INCLUDE_R18 = "include_r18_in_home_feed"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    /** 首页推荐是否包含 R-18 作品，Lite 版恒为 false，默认关闭。 */
    @JvmStatic
    fun isEnabled(): Boolean {
        if (BuildConfig.IS_LITE) {
            return false
        }
        return store.decodeBool(KEY_INCLUDE_R18, false)
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        if (BuildConfig.IS_LITE) {
            store.encode(KEY_INCLUDE_R18, false)
            return
        }
        if (enabled && !SessionManager.hasValidWebSession()) {
            store.encode(KEY_INCLUDE_R18, false)
            return
        }
        store.encode(KEY_INCLUDE_R18, enabled)
    }
}
