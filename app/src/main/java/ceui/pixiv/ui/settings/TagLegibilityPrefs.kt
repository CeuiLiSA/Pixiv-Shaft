package ceui.pixiv.ui.settings

import ceui.pixiv.witstudio.theme.V3TagLegibility
import com.tencent.mmkv.MMKV

/**
 * 「标签原文亮暗度」的**设备本地**存储：白天 / 黑暗两条，0..100，默认 0。
 *
 * 为什么不放 [ceui.lisa.utils.Settings]：辨识度是「眼睛 + 屏幕」的组合结果，换设备后同一个值
 * 不一定合适，**不具备跨设备性**。而 Settings 是备份 / 云端的载荷本体 —— 放进去就得在导出侧
 * 逐字段摘（`Shaft-Backup.json` 与云端 `PUT /v1/settings/{uid}` 两个出口各来一遍），还得回答
 * 「同机还原会不会把本机值清零」。
 *
 * 本仓库对「设置页上的用户选项、但不该跨设备」已有现成先例，本对象照此办理：
 *
 * - [ceui.pixiv.i18n.AppLocales]：设置页「外观 → 语言」，真源在 MMKV + AppCompat per-app
 *   locale，[ceui.lisa.utils.Settings] 里那个老字段已标 `@deprecated` 仅供一次性迁移；
 * - [ceui.pixiv.muzei.MuzeiPrefs]：Muzei 壁纸来源，注释写明「直接走 MMKV 而不进 Settings 的
 *   Gson 大对象」；
 * - [ceui.pixiv.ui.novel.reader.settings.ReaderSettings] / `ComicReaderSettings`：阅读器设置，
 *   各自独立 MMKV namespace。
 *
 * 于是备份 / 云端**天然看不到这两条** —— 导出的是 Settings 对象，不在其中的字段根本不会被
 * 序列化，不需要任何排除名单，也不存在「还原后被清零」的取舍。
 *
 * 独立 namespace（不挤默认 MMKV）与阅读器 / 快照那几套同规矩：文件级损坏不波及全局。
 */
object TagLegibilityPrefs {

    private const val MMKV_ID = "tag_legibility_v1"
    private const val KEY_LIGHT = "boost_light"
    private const val KEY_DARK = "boost_dark"
    private const val MAX = 100

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    /** 白天模式的强度，0..100。 */
    @JvmStatic
    fun light(): Int = store.decodeInt(KEY_LIGHT, 0).coerceIn(0, MAX)

    /** 黑暗模式的强度，0..100。两条独立：只想提高深色下的辨识度就只动它。 */
    @JvmStatic
    fun dark(): Int = store.decodeInt(KEY_DARK, 0).coerceIn(0, MAX)

    /** 两条一起写，越界钳到 0..100。 */
    @JvmStatic
    fun save(light: Int, dark: Int) {
        store.encode(KEY_LIGHT, light.coerceIn(0, MAX))
        store.encode(KEY_DARK, dark.coerceIn(0, MAX))
    }

    /** 两条一起归零。切主题色时用（派生色整体换了，旧值语义不再对应）。 */
    @JvmStatic
    fun reset() {
        save(0, 0)
    }

    /**
     * 把两条推给 witstudio 的 [V3TagLegibility]。
     *
     * witstudio 不依赖 `:app`，[ceui.pixiv.witstudio.theme.V3Palette.from] 读不到本对象，只能
     * 这样单向注入。启动时（`Shaft#onCreate`）与设置页改动后各调一次。
     */
    @JvmStatic
    fun applyToWitStudio() {
        V3TagLegibility.setBoosts(light() / 100f, dark() / 100f)
    }
}
