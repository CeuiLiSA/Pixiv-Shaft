package ceui.loxia

import android.app.Activity
import android.content.Context
import android.content.Intent
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 打开「网络测试」页，与侧边栏 nav_network_test 走同一条 TemplateActivity 路由，不新增页面。
 * 目前的调用方是 [ceui.pixiv.feeds.host.ShaftFeedHost.openNetworkTest]（列表全屏错误态的
 * 网络类错误入口）；以后别处再要跳这个页也走这里，别各自手搓 EXTRA_FRAGMENT 字面量。
 */
fun openNetworkTestPage(context: Context) {
    val intent = Intent(context, TemplateActivity::class.java)
        .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DEBUG_NETWORK_TEST.key)
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
