package ceui.loxia

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.ui.background.AppBackground
import ceui.pixiv.utils.NetworkStateManager
import com.tencent.mmkv.MMKV


interface ServicesProvider {
    val prefStore: MMKV
    val networkStateManager: NetworkStateManager
    val entityWrapper: EntityWrapper
    val appBackground: AppBackground
}

fun Fragment.requireEntityWrapper(): EntityWrapper {
    return requireActivity().requireEntityWrapper()
}

fun FragmentActivity.requireEntityWrapper(): EntityWrapper {
    return (application as ServicesProvider).entityWrapper
}

fun Fragment.requireAppBackground(): AppBackground {
    return requireActivity().requireAppBackground()
}

fun FragmentActivity.requireAppBackground(): AppBackground {
    return (application as ServicesProvider).appBackground
}

fun Fragment.requireNetworkStateManager(): NetworkStateManager {
    return requireActivity().requireNetworkStateManager()
}

fun FragmentActivity.requireNetworkStateManager(): NetworkStateManager {
    return (application as ServicesProvider).networkStateManager
}
