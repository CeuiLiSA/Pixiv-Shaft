package ceui.pixiv.websocket

import android.content.Context

/**
 * 进程级唯一的 [NetworkMonitor]。
 *
 * [NetworkMonitor] 自己已经用 `shareIn(WhileSubscribed)` 保证「同一个实例无论多少订阅者，
 * 只注册一个 [android.net.ConnectivityManager.NetworkCallback]」。但那个保证是**按实例**的 ——
 * 聊天 WebSocket 和收藏队列各 `new` 一个的话，系统回调仍然是两个，与那段设计的意图相悖
 * （见该类 KDoc 里关于回调注册数上限的说明）。
 *
 * 收敛到一个实例还有个附带好处：收藏队列的订阅是进程级常驻的，于是回调始终注册着，
 * WebSocket 那边断线重连时订阅上来就能立刻拿到 replay 的当前连通性，不用等一次系统回调。
 */
object AppNetworkMonitor {

    @Volatile
    private var instance: NetworkMonitor? = null

    fun get(context: Context): NetworkMonitor {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: NetworkMonitor(context.applicationContext).also { instance = it }
        }
    }
}
