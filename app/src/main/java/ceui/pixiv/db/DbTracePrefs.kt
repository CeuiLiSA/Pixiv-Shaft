package ceui.pixiv.db

import com.tencent.mmkv.MMKV

/**
 * 「打印所有数据库耗时」开关（设置 → 试验性）。
 *
 * 打开后 [DbCallTracker] 会把**每一条** SQL 的耗时写进日志（tag `DbCallTracker`），
 * 而不只是超过阈值的那几条。用来现场排查「到底哪条查询在拖」——比看时间线猜要快得多。
 *
 * 独立 MMKV namespace，不进 `Settings`：这是排查开关，不该跟着备份/还原跑到别的设备上，
 * 也不该占设置模型的一格。与 `TagLegibilityPrefs` / `MuzeiPrefs` 同一套规矩。
 *
 * 默认关。开着的时候日志量很大（每条查询一行），只适合短时间排查。
 */
object DbTracePrefs {

    private const val MMKV_ID = "db_call_trace_v1"
    private const val KEY_ENABLED = "enabled"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    @JvmStatic
    var isEnabled: Boolean
        get() = runCatching { store.decodeBool(KEY_ENABLED, false) }.getOrDefault(false)
        set(value) {
            runCatching { store.encode(KEY_ENABLED, value) }
        }
}
