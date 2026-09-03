package ceui.pixiv.ui.translate

import android.app.Activity
import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.usage.Nana7miQuotaFormat
import ceui.lisa.utils.ClipBoardUtils
import com.blankj.utilcode.util.ActivityUtils
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * GoogleWebTranslator 走 translate.googleapis.com,国内必须有代理。
 * 翻译失败时弹一条 WitDialog 直接告诉用户「需要代理」— 避免只 toast 一句模糊的「翻译失败」
 * 让人以为是 app bug 反复重试。
 *
 * 拿当前 foreground Activity 用 [ActivityUtils.getTopActivity];AndroidUtilCode 内部维护
 * lifecycle callbacks,这里不用再自己注册。fallback:拿不到 activity / 已 finishing 就静默 —
 * 调用方仍会走原 toast 路径,不会缺少错误反馈。
 */
internal fun promptProxyNeededIfPossible() {
    showTranslateDialogIfPossible { activity ->
        WitDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.translate_proxy_required_title)
            .setMessage(R.string.translate_proxy_required_message)
    }
}

/**
 * 翻译失败的统一提示:走自定义 AI 引擎(#975)时「需要代理」是误导 — 失败多半是
 * key 无效/模型名错/服务没起,把真实错误摆给用户;走 Google 引擎才提示代理。
 */
internal fun promptTranslateFailedIfPossible(e: Exception?) {
    // 取消异常(「Job was cancelled」之类)不是翻译失败:是页面/任务被取消,
    // 不该把原始 message 当错误弹给用户,静默跳过即可(调用方已按取消语义处理)。
    if (e is CancellationException) {
        Timber.d("TranslateProxyHint: skip cancelled translation error: %s", e.message)
        return
    }
    // 云翻译额度用完：这不是「失败」而是「用到头了」，说清楚什么时候恢复、把用量页递过去。
    if (e is CloudTranslateQuotaException) {
        promptCloudQuotaExhausted(e)
        return
    }
    // 异常本身说是云翻译的就按云翻译报：服务端刚关停时 CloudTranslator 会先把开关翻掉再抛，
    // 这时再问 isActive() 已经是 false，会误弹成「需要代理」。
    val cloud = e is CloudTranslateException ||
        (!AiTranslator.isActive() && CloudTranslator.isActive())
    if (!AiTranslator.isActive() && !cloud) {
        promptProxyNeededIfPossible()
        return
    }
    showTranslateDialogIfPossible { activity ->
        val body = buildFailureBody(activity, e)
        WitDialog.MessageDialogBuilder(activity)
            .setTitle(if (cloud) R.string.cloud_translate_failed_title else R.string.ai_translate_failed_title)
            .setMessage(body)
            .addAction(activity.getString(R.string.string_120)) { dialog, _ ->
                ClipBoardUtils.putTextIntoClipboard(activity, body)
                dialog.dismiss()
            }
    }
}

private fun promptCloudQuotaExhausted(e: CloudTranslateQuotaException) {
    showTranslateDialogIfPossible { activity ->
        val resetIn = e.resetInMs?.let { Nana7miQuotaFormat.duration(activity, it) }
        val message = when {
            resetIn == null -> activity.getString(R.string.cloud_translate_quota_unknown)
            e.scope == "uid_weekly" -> activity.getString(R.string.cloud_translate_quota_weekly, resetIn)
            else -> activity.getString(R.string.cloud_translate_quota_session, resetIn)
        }
        WitDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.cloud_translate_quota_title)
            .setMessage(message)
            .addAction(activity.getString(R.string.nana7mi_quota_snack_action)) { dialog, _ ->
                activity.startActivity(
                    Intent(activity, TemplateActivity::class.java)
                        .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NANA7MI_USAGE.key),
                )
                dialog.dismiss()
            }
    }
}

/** 错误码 + 错误消息两行拼成弹窗正文，复制按钮把同一段文字写进剪贴板。 */
private fun buildFailureBody(activity: Activity, e: Exception?): String {
    val code = failureCode(e)
    val codeText = code?.toString()
        ?: activity.getString(R.string.ai_translate_error_code_unknown)
    val message = failureMessage(e, code, activity)
    return activity.getString(R.string.ai_translate_error_code_label) + "：" + codeText + "\n" +
        activity.getString(R.string.ai_translate_error_message_label) + "：" + message
}

/** HTTP 类失败有明确状态码；网络/解析等失败没有，返回 null 由 UI 显示“未知”。 */
private fun failureCode(e: Exception?): Int? = when (e) {
    is AiTranslator.RetryableApiException -> e.code
    is AiTranslator.ApiConfigException -> e.code
    is CloudTranslateException -> e.code
    else -> null
}

/**
 * 错误消息去掉「HTTP xxx: 」前缀，避免和错误码行重复；
 * 拿不到消息时给占位文案，而不是再贴一遍标题。
 */
private fun failureMessage(e: Exception?, code: Int?, activity: Activity): String {
    val raw = when (e) {
        is AiTranslator.RetryableApiException -> e.message
        is AiTranslator.ApiConfigException -> e.message
        is CloudTranslateException -> e.message
        else -> e?.message ?: e?.toString()
    }.orEmpty()
    val stripped = if (code != null && raw.startsWith("HTTP $code: ")) {
        raw.removePrefix("HTTP $code: ")
    } else {
        raw
    }
    return stripped.ifBlank { activity.getString(R.string.ai_translate_failed_no_detail) }
}

private fun showTranslateDialogIfPossible(build: (Activity) -> WitDialog.MessageDialogBuilder) {
    val activity: Activity? = ActivityUtils.getTopActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Timber.tag("TranslateProxyHint").w("no resumed activity, skip dialog")
        return
    }
    activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        try {
            build(activity)
                .addAction(0, android.R.string.ok, WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Timber.tag("TranslateProxyHint").w(e, "show dialog failed")
        }
    }
}
