package ceui.pixiv.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.utils.Params

/**
 * 订阅「全局 R-18 过滤开关变了」，随 [LifecycleOwner] 销毁自动注销。
 *
 * 这个开关是长按侧边栏头像切的：那一刻抽屉是开着的，底下的页面不会经过 onResume，所以只能由
 * 发送方主动通知。列表收到后按当前设置重算条目显隐即可，不必重新拉一次数据。
 */
internal fun LifecycleOwner.observeR18FilterChanges(context: Context, block: () -> Unit) {
    val broadcastManager = LocalBroadcastManager.getInstance(context)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            block()
        }
    }
    broadcastManager.registerReceiver(receiver, IntentFilter(Params.R18_FILTER_CHANGED))
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            broadcastManager.unregisterReceiver(receiver)
        }
    })
}