package ceui.loxia

import ceui.pixiv.utils.NetworkStateManager
import com.tencent.mmkv.MMKV


interface ServicesProvider {
    val prefStore: MMKV
    val networkStateManager: NetworkStateManager
}