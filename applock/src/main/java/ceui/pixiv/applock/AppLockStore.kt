package ceui.pixiv.applock

import com.tencent.mmkv.MMKV

/**
 * 开关与自动锁定时长，存设备本地的独立 MMKV。
 *
 * 必须同步可读：第一个 Activity resume 时就要知道该不该锁，异步存储在那一刻还拿不到值。
 */
internal class AppLockStore(private val mmkv: MMKV = MMKV.mmkvWithID(STORE_ID)) {

    var isEnabled: Boolean
        get() = mmkv.decodeBool(KEY_ENABLED, false)
        set(value) {
            mmkv.encode(KEY_ENABLED, value)
        }

    var relockTimeout: RelockTimeout
        get() = RelockTimeout.fromName(mmkv.decodeString(KEY_TIMEOUT, null))
        set(value) {
            mmkv.encode(KEY_TIMEOUT, value.name)
        }

    private companion object {
        const val STORE_ID = "app_lock"
        const val KEY_ENABLED = "enabled"
        const val KEY_TIMEOUT = "relock_timeout"
    }
}
