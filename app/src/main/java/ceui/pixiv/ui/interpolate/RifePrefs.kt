package ceui.pixiv.ui.interpolate

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Local
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV

/**
 * 「动图 AI 补帧（RIFE）」开关的**设备本地**存储。
 *
 * 为什么不放 [ceui.lisa.utils.Settings]：这个开关的语义是「模型已在本机落盘」的派生状态
 * —— 见 `FragmentSettingsViewing` 的落盘约束（开关开 ⇒ 模型一定在位）。模型是几十 MB 的
 * 本地文件，换设备后并不存在，把开关带过去只会得到一个「开着但没有模型」的空壳。
 *
 * 而 Settings 是备份 / 云端的载荷本体，一共有三个出口会把它整份带走：
 *
 *  1. 设置页导出备份的 `Shaft-Backup.json`（[ceui.lisa.utils.BackupUtils.writeBackupToFile]）；
 *  2. MoonSync 云端 `PUT /v1/settings/{uid}`（[ceui.pixiv.ui.settings.MoonSync.uploadToCloud]）；
 *  3. Android 自动备份 / 换机传输（`allowBackup=true`，`shared_prefs/local_data.xml` 整份带走）。
 *
 * 放进去就得在这三个出口各摘一次，还得回答「同机还原会不会把本机值清零」。本仓库对
 * 「设置页上的用户选项、但不该跨设备」已有现成先例（[ceui.pixiv.ui.settings.TagLegibilityPrefs]、
 * [ceui.pixiv.muzei.MuzeiPrefs]、[ceui.pixiv.ui.novel.local.LocalLibraryStore]）：**走设备
 * 本地 MMKV，不进 Settings**。于是前两条路径天然看不到它（不在 Settings 里的字段根本不会被
 * 序列化），第三条由 `backup_rules.xml` / `data_extraction_rules.xml` 对这个 MMKV 文件整份
 * 排除兜住。三条路径都拿不到 ⇒ 换设备 / 还原后读到的就是默认的 false。
 *
 * 独立 namespace（不挤默认 MMKV）与阅读器 / 快照那几套同规矩：文件级损坏不波及全局。
 */
object RifePrefs {

    private const val MMKV_ID = "rife_state_v1"
    private const val KEY_ENABLE = "ugoira_rife_enable"

    /**
     * 老 [ceui.lisa.utils.Settings] 里的同名字段（已从 Settings 删除）。
     * 只用于一次性迁移读取；迁移后下一次序列化就不再包含它。
     */
    private const val LEGACY_KEY = "ugoiraRifeEnable"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    /** 补帧开关，默认关闭。 */
    @JvmStatic
    fun isEnabled(): Boolean = store.decodeBool(KEY_ENABLE, false)

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        store.encode(KEY_ENABLE, enabled)
    }

    /**
     * 一次性迁移：把老版本存在 `Settings` 里的 `ugoiraRifeEnable` 搬进本对象。
     *
     * 只在**本机模型确实在位**时继承旧值。老版本的开关可以先于模型存在（点开 → 跳下载页
     * → 没下就退出），那种「开着但没有模型」的历史状态按新口径直接丢弃 —— 否则等于把刚
     * 消灭掉的状态又搬了一次家。
     *
     * 无论是否继承，只要老 key 出现过就调一次 [Local.setSettings] 触发重新序列化，把它从
     * 本地 prefs 的 JSON 里挤掉（Settings 已无此字段），于是它也不会再进备份 / 云端。
     *
     * 须在 `Shaft#onCreate` 里、[Shaft.sSettings] 赋值之后调用一次。
     */
    @JvmStatic
    fun migrateLegacy(legacySettingsJson: String?, context: Context) {
        if (legacySettingsJson.isNullOrBlank() || !legacySettingsJson.contains(LEGACY_KEY)) return
        val legacy = runCatching {
            JsonParser.parseString(legacySettingsJson).asJsonObject.get(LEGACY_KEY)?.asBoolean
        }.getOrNull() ?: return
        if (legacy && RifeInterpolator.isAvailable(context)) {
            setEnabled(true)
        }
        Local.setSettings(Shaft.sSettings)
    }
}