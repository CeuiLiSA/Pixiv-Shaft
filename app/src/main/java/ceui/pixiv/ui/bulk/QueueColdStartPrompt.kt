package ceui.pixiv.ui.bulk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.witstudio.dialog.WitSkinManager
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import timber.log.Timber

/**
 * Cold-start 检测到 download_queue 里有 PENDING 行时，等第一个真正可见的 Activity
 * 弹一个 QMUI dialog 询问用户是否继续下载。
 *
 * 抽到独立文件、不嵌在 [QueueDownloadManager] 里，是因为它跟主循环 / 状态机
 * 完全无耦合 —— 只用 Application context + 一个回调通知"用户同意继续"。
 *
 * 行为跟原 inline 实现 1:1 等价：
 *   - 等到第一个 RESUMED 且未 finishing/destroyed 的 Activity，弹一次就反注册
 *   - 用户点"继续" → 调用 [onResumeAccepted]
 *   - 用户点"暂时不下" → dialog dismiss，不调回调（QueueDownloadManager 保持 paused）
 *   - 极端 attach 失败（窗口已坏）→ catch 后 fallback 调 [onResumeAccepted]，
 *     免得任务永远停在 paused
 */
internal fun promptResumeOnFirstActivity(
    app: Application,
    pendingCount: Int,
    onResumeAccepted: () -> Unit,
) {
    val cb = object : Application.ActivityLifecycleCallbacks {
        @Volatile var fired = false
        override fun onActivityResumed(activity: Activity) {
            if (fired) return
            if (activity.isFinishing || activity.isDestroyed) return
            fired = true
            app.unregisterActivityLifecycleCallbacks(this)
            showResumePrompt(activity, pendingCount, onResumeAccepted)
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
    app.registerActivityLifecycleCallbacks(cb)
}

private fun showResumePrompt(
    activity: Activity,
    pendingCount: Int,
    onAccepted: () -> Unit,
) {
    // WitDialog 必须在主线程展示
    activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        try {
            WitDialog.MessageDialogBuilder(activity)
                .setTitle(R.string.bulk_resume_prompt_title)
                .setMessage(activity.getString(R.string.bulk_resume_prompt_message, pendingCount))
                .setSkinManager(WitSkinManager.defaultInstance(activity))
                .addAction(0, activity.getString(R.string.bulk_resume_prompt_decline), WitDialogAction.ACTION_PROP_NEUTRAL) { d, _ ->
                    // 保持 paused —— 用户可去 下载管理 → 批量队列 手动点 "继续"
                    Timber.tag(TAG).i("user declined cold-start resume; staying paused")
                    d.dismiss()
                }
                .addAction(0, activity.getString(R.string.bulk_resume_prompt_continue)) { d, _ ->
                    Timber.tag(TAG).i("user confirmed cold-start resume; pending=$pendingCount")
                    onAccepted()
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "failed to show resume prompt; auto-resuming as fallback")
            // 极端情况（窗口已坏）下 fallback 到自动恢复，免得任务永远卡在 paused
            onAccepted()
        }
    }
}

private const val TAG = "QueueColdStartPrompt"
