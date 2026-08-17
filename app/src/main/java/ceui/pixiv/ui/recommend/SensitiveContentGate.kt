package ceui.pixiv.ui.recommend

import android.app.Activity
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import timber.log.Timber

/**
 * 当前最热 / 本月收藏 / 操作记录 三个服务端聚合入口里混着其他用户的作品,可能含 R-18。
 * 进去前弹一次 QMUI 警示框:[取消查看] 直接 dismiss 不进;[坚持查看] 记一个全局 flag
 * 并放行。flag 一旦置位,这三个入口以后都不再弹(用户只需确认一次)。
 *
 * 拦截点统一放在 [ceui.lisa.activities.MainActivity.handleDrawerAction] 的 startActivity
 * 处,所以 drawer 和「我的」页(都走同一个 handleDrawerAction)两条路径都被这层 gate 覆盖。
 */
object SensitiveContentGate {

    private const val PREF_KEY_ACKED = "sensitive_content_gate_acked"
    private const val TAG = "SensitiveGate"

    @JvmStatic
    fun isAcked(): Boolean =
        Shaft.sPreferences?.getBoolean(PREF_KEY_ACKED, false) ?: false

    /**
     * 已确认过 → 直接跑 [onProceed];否则弹框,点「坚持查看」才置 flag 再 [onProceed]。
     * 取消 / 点框外 / 拿不到可用 activity → 什么都不做(安全默认 = 不进)。
     */
    @JvmStatic
    fun gateOrProceed(activity: Activity?, onProceed: Runnable) {
        if (isAcked()) {
            onProceed.run()
            return
        }
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Timber.tag(TAG).w("no usable activity, skip (treated as cancel)")
            return
        }
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                WitDialog.MessageDialogBuilder(activity)
                    .setTitle(R.string.sensitive_gate_title)
                    .setMessage(R.string.sensitive_gate_message)
                    .addAction(0, activity.getString(R.string.sensitive_gate_cancel), WitDialogAction.ACTION_PROP_NEUTRAL) { d, _ ->
                        d.dismiss()
                    }
                    .addAction(0, activity.getString(R.string.sensitive_gate_proceed), WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                        Shaft.sPreferences?.edit()?.putBoolean(PREF_KEY_ACKED, true)?.apply()
                        d.dismiss()
                        onProceed.run()
                    }
                    .show()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "show gate dialog failed")
            }
        }
    }
}
