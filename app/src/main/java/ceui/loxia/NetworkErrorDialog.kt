package ceui.loxia

import android.app.Activity
import android.content.Context
import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import ceui.pixiv.chat.base.isNetworkClassError
import com.blankj.utilcode.util.ActivityUtils
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局防重入：同一时刻只允许一个「网络错误」弹窗。首页这类多 Feed 并存的页面，一次网络故障
 * 可能同时让多个列表失败（各自走 [showNetworkErrorDialog]），不加锁就会叠出一串 QMUIDialog。
 * 已有弹窗在展示时，后续失败直接跳过（视为已处理），等当前弹窗关闭后下一次失败再弹。
 */
private val networkErrorDialogShowing = AtomicBoolean(false)

/** 打开「网络测试」页，与侧边栏 nav_network_test 走同一条 TemplateActivity 路由。 */
fun openNetworkTestPage(context: Context) {
    val intent = Intent(context, TemplateActivity::class.java)
        .putExtra(TemplateActivity.EXTRA_FRAGMENT, "网络测试")
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

/**
 * 网络类错误（断网 / 超时 / SSL）弹 QMUIDialog 而不是 toast：文案复用
 * [getHumanReadableMessage]，主操作「去网络测试」；非网络类错误返回 false，
 * 调用方维持原来的 toast 行为。返回 true 表示弹窗已展示。
 */
fun showNetworkErrorDialog(activity: Activity?, throwable: Throwable): Boolean {
    if (!throwable.isNetworkClassError()) return false
    val host = activity ?: ActivityUtils.getTopActivity()
    if (host == null || host.isFinishing || host.isDestroyed) return false
    // 已有网络错误弹窗在展示：跳过本次，调用方不再弹 toast（返回 true = 已处理）。
    if (!networkErrorDialogShowing.compareAndSet(false, true)) return true
    val message = runCatching { throwable.getHumanReadableMessage(host) }
        .getOrNull()
        ?: host.getString(R.string.list_load_failed_tap_retry)
    host.runOnUiThread {
        try {
            // runOnUiThread 排队期间 Activity 可能已被销毁：弹窗不显示就复位防重入标志，
            // 否则标志会永久卡在 true，之后所有网络错误都不再弹窗。
            if (host.isFinishing || host.isDestroyed) {
                networkErrorDialogShowing.set(false)
                return@runOnUiThread
            }
            val dialog = QMUIDialog.MessageDialogBuilder(host)
                .setTitle(R.string.network_test_error_dialog_title)
                .setMessage(message)
                .setSkinManager(QMUISkinManager.defaultInstance(host))
                .addAction(R.string.network_test_error_action) { d, _ ->
                    d.dismiss()
                    openNetworkTestPage(host)
                }
                .addAction(0, android.R.string.ok, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ -> d.dismiss() }
                .create()
            dialog.setOnDismissListener { networkErrorDialogShowing.set(false) }
            dialog.show()
        } catch (e: Exception) {
            Timber.w(e, "show network error dialog failed")
            networkErrorDialogShowing.set(false)
            Common.showToast(message)
        }
    }
    return true
}
