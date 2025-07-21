package ceui.pixiv.utils

import ceui.lisa.activities.Shaft
import timber.log.Timber

object VpnRetryHelper {

    private val pendingRequests = mutableMapOf<String, () -> Unit>()
    private var lastVpnState = NetworkStateManager.isGoogleCanBeAccessed(Shaft.getContext())

    /**
     * 添加一个待 VPN 开启后执行的请求，避免重复（通过 token 唯一标识）
     */
    fun pushRequest(requestToken: String, block: () -> Unit) {
        val vpnActive = NetworkStateManager.isGoogleCanBeAccessed(Shaft.getContext())
        Timber.d("VpnRetryHelper: pushRequest($requestToken), vpnActive=$vpnActive")

        if (vpnActive) {
            Timber.d("VpnRetryHelper: VPN is active, executing request immediately: $requestToken")
            block()
        } else {
            if (!pendingRequests.containsKey(requestToken)) {
                Timber.d("VpnRetryHelper: VPN is not active, storing request: $requestToken")
                pendingRequests[requestToken] = block
            } else {
                Timber.d("VpnRetryHelper: Duplicate requestToken ignored: $requestToken")
            }
        }
    }

    /**
     * 在 onResume 或 app 返回前台时调用
     */
    fun onResume() {
        val currentVpnState = NetworkStateManager.isGoogleCanBeAccessed(Shaft.getContext())
        Timber.d("VpnRetryHelper: onResume() called, lastVpnState=$lastVpnState, currentVpnState=$currentVpnState")

        if (!lastVpnState && currentVpnState) {
            Timber.d("VpnRetryHelper: VPN was off, now on. Flushing pending requests.")
            flushRequests()
        } else {
            Timber.d("VpnRetryHelper: No state change or VPN still not active.")
        }

        lastVpnState = currentVpnState
    }

    private fun flushRequests() {
        if (pendingRequests.isEmpty()) {
            Timber.d("VpnRetryHelper: No pending requests to flush.")
            return
        }

        Timber.d("VpnRetryHelper: Flushing ${pendingRequests.size} request(s).")
        for ((token, action) in pendingRequests) {
            try {
                Timber.d("VpnRetryHelper: Executing pending request: $token")
                action()
            } catch (e: Exception) {
                Timber.e(e, "VpnRetryHelper: Error while executing request: $token")
            }
        }
        pendingRequests.clear()
        Timber.d("VpnRetryHelper: All pending requests cleared.")
    }

    fun clearAll() {
        Timber.d("VpnRetryHelper: clearAll() called, clearing ${pendingRequests.size} request(s).")
        pendingRequests.clear()
    }

    fun hasPending(): Boolean {
        val result = pendingRequests.isNotEmpty()
        Timber.d("VpnRetryHelper: hasPending() = $result")
        return result
    }
}
