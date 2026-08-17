package ceui.loxia

import android.app.Activity
import android.content.Context
import android.content.Intent
import ceui.lisa.activities.TemplateActivity

/**
 * 打开「网络测试」页，与侧边栏 nav_network_test 走同一条 TemplateActivity 路由。
 * 空态 / 全屏错误态的网络类错误入口共用这里，不新增页面。
 */
fun openNetworkTestPage(context: Context) {
    val intent = Intent(context, TemplateActivity::class.java)
        .putExtra(TemplateActivity.EXTRA_FRAGMENT, "网络测试")
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
