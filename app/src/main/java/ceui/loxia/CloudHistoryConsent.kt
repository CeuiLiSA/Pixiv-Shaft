package ceui.loxia

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.pixiv.db.HistoryBackfill
import ceui.pixiv.db.HistoryReporter
import ceui.pixiv.session.SessionManager
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 浏览记录云同步(pixshaft-api)的同意与开关。浏览历史上传默认开启,但首次会弹一次
 * 同意框让用户明确选择是否关闭(见 issue #889)。开关本身存在 [ceui.lisa.utils.Settings],
 * 真正的上传由 [HistoryReporter] 在每次入队/flush 时读取该开关决定是否执行。
 */
object CloudHistoryConsent {

    private const val TAG = "CloudHistoryConsent"

    /** 持久化开关 + 同意状态;关闭时立刻丢弃尚未上传的缓冲。 */
    private fun persist(enabled: Boolean, consentShown: Boolean) {
        Shaft.sSettings.isCloudHistorySync = enabled
        Shaft.sSettings.isCloudHistoryConsentShown = consentShown
        // 关同步顺手清回填标记:关同步期间的浏览只落本地,重开时回填重跑一遍才能把
        // 这段缺口补传上去(回填幂等,已在云端的行都是 no-op)。见 HistoryBackfill。
        if (!enabled) Shaft.sSettings.cloudHistoryBackfillDoneUid = 0L
        Local.setSettings(Shaft.sSettings)
        if (!enabled) HistoryReporter.clearPending()
        // Tell the server the new toggle state so the admin "opted out" list is
        // accurate. Fire-and-forget; the server bumps its counter only on a real flip.
        HistoryReporter.reportSyncPref(enabled)
    }

    /** 设置页开关直接调用:用户手动拨开关本身就是一次明确选择,顺手标记同意框无需再弹。 */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        persist(enabled, consentShown = true)
        // 开启的这一刻把存量本地历史回填到云端(#989)。自带幂等/去重门槛,重复调无害。
        if (enabled) HistoryBackfill.maybeSchedule()
    }

    /**
     * 首次进浏览历史页(已登录)时弹一次同意框。已弹过 / 未登录则什么都不做。
     * @param onResolved 用户选了 KEEP/STOP 后回调,调用方可据此刷新列表(选 KEEP 后
     *                   读取会切到云端,列表需要重新加载才能反映出来)。
     * @return 是否真的弹了对话框。
     */
    @JvmStatic
    @JvmOverloads
    fun maybeShowConsent(activity: FragmentActivity, onResolved: (() -> Unit)? = null): Boolean {
        if (Shaft.sSettings.isCloudHistoryConsentShown) return false
        if (SessionManager.loggedInUid <= 0L) return false // 未登录不上传,不必打扰
        if (activity.isFinishing || activity.isDestroyed) return false

        Timber.tag(TAG).i("[consent] showing one-time dialog")
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.cloud_history_consent_title)
            .setMessage(R.string.cloud_history_consent_message)
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(activity.getString(R.string.cloud_history_consent_stop)) { d, _ ->
                Timber.tag(TAG).i("[consent] user chose STOP")
                persist(enabled = false, consentShown = true)
                d.dismiss()
                onResolved?.invoke()
            }
            .addAction(0, activity.getString(R.string.cloud_history_consent_keep),
                QMUIDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                Timber.tag(TAG).i("[consent] user chose KEEP")
                persist(enabled = true, consentShown = true)
                HistoryBackfill.maybeSchedule() // 明确同意后立刻回填存量本地历史(#989)
                d.dismiss()
                onResolved?.invoke()
            }
            .create()
            .show()
        // 「弹一次」:一旦展示就记下,即使用户点返回不选也不再纠缠(保持默认开启的选择)。
        persist(Shaft.sSettings.isCloudHistorySync, consentShown = true)
        return true
    }

    /** 设置页"清除云端浏览记录":二次确认后删除该 uid 在云端的全部浏览记录(本地不动)。 */
    @JvmStatic
    fun clearCloudHistory(activity: FragmentActivity, uid: Long) {
        if (uid <= 0L) {
            Common.showToast(activity.getString(R.string.moon_login_required))
            return
        }
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.clear_cloud_history_confirm_title)
            .setMessage(R.string.clear_cloud_history_confirm_message)
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(activity.getString(R.string.string_187)) { d, _ -> d.dismiss() }
            .addAction(0, activity.getString(R.string.sure), QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                activity.lifecycleScope.launch {
                    try {
                        val ack = Client.pixshaft.clearHistory(uid, null)
                        Timber.tag(TAG).i("[clear] deleted=%d", ack.deleted)
                        Common.showToast(activity.getString(R.string.clear_cloud_history_success))
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "[clear] failed")
                        Common.showToast(activity.getString(R.string.clear_cloud_history_failed))
                    }
                }
            }
            .create()
            .show()
    }
}
