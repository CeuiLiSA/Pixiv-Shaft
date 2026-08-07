package ceui.pixiv.ui.translate

import android.app.Activity
import ceui.lisa.R
import com.blankj.utilcode.util.ActivityUtils
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import timber.log.Timber

/**
 * GoogleWebTranslator 走 translate.googleapis.com,国内必须有代理。
 * 翻译失败时弹一条 QMUIDialog 直接告诉用户「需要代理」— 避免只 toast 一句模糊的「翻译失败」
 * 让人以为是 app bug 反复重试。
 *
 * 拿当前 foreground Activity 用 [ActivityUtils.getTopActivity];AndroidUtilCode 内部维护
 * lifecycle callbacks,这里不用再自己注册。fallback:拿不到 activity / 已 finishing 就静默 —
 * 调用方仍会走原 toast 路径,不会缺少错误反馈。
 */
internal fun promptProxyNeededIfPossible() {
    showTranslateDialogIfPossible { activity ->
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.translate_proxy_required_title)
            .setMessage(R.string.translate_proxy_required_message)
    }
}

/**
 * 翻译失败的统一提示:走自定义 AI 引擎(#975)时「需要代理」是误导 — 失败多半是
 * key 无效/模型名错/服务没起,把真实错误摆给用户;走 Google 引擎才提示代理。
 */
internal fun promptTranslateFailedIfPossible(e: Exception?) {
    if (!AiTranslator.isActive()) {
        promptProxyNeededIfPossible()
        return
    }
    val detail = e?.message ?: e?.toString() ?: ""
    showTranslateDialogIfPossible { activity ->
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.ai_translate_failed_title)
            .setMessage(detail.ifBlank { activity.getString(R.string.ai_translate_failed_title) })
    }
}

private fun showTranslateDialogIfPossible(build: (Activity) -> QMUIDialog.MessageDialogBuilder) {
    val activity: Activity? = ActivityUtils.getTopActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Timber.tag("TranslateProxyHint").w("no resumed activity, skip dialog")
        return
    }
    activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        try {
            build(activity)
                .setSkinManager(QMUISkinManager.defaultInstance(activity))
                .addAction(0, android.R.string.ok, QMUIDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Timber.tag("TranslateProxyHint").w(e, "show dialog failed")
        }
    }
}
