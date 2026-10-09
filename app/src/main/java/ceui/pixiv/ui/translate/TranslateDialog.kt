package ceui.pixiv.ui.translate

import android.content.Context
import ceui.lisa.R
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.dialog.WitDialog
import java.util.concurrent.atomic.AtomicBoolean

/** 漫画进度与设置页测试共用的状态文案；上游提示只在等待阶段展示。 */
internal fun AiTranslatePhase.statusText(context: Context): String = when (this) {
    is AiTranslatePhase.Thinking -> reasoningContent.ifBlank { context.getString(R.string.ai_translate_thinking) }
    AiTranslatePhase.Generating -> context.getString(R.string.ocr_translating)
}

/**
 * 详情页标题/简介翻译与评论翻译共享的「思考中」阶段提示与译文弹窗。
 *
 * 两处翻译入口(见 [ceui.pixiv.ui.detail.translateTitleAndCaption] 与
 * [ceui.pixiv.ui.comments.translateComment])此前各自复制了一份几乎相同的 WitDialog 装配
 * 与阶段 toast,这里收拢成共享成员,避免后续改一处漏一处。
 * 同一次操作里只提示一次,避免流式片段或并发请求反复弹 toast。
 * 回调来自 IO 线程(见 [AiTranslator] 的流式解析),所以用 [AtomicBoolean] 而不是裸 var。
 */
internal fun onceThinkingPhase(
    showToast: (Int) -> Unit = { Common.showToast(it) },
): (AiTranslatePhase) -> Unit {
    val shown = AtomicBoolean(false)
    return { phase ->
        if (phase is AiTranslatePhase.Thinking && shown.compareAndSet(false, true)) {
            // 首个 delta 可能只有一个字。一次性 Toast 使用完整的阶段提示，
            // 上游增量文字留给能持续更新的漫画状态栏和设置页。
            showToast(R.string.ai_translate_thinking)
        }
    }
}

/**
 * 弹出译文弹窗(挂 SkinManager 跟随日夜皮肤),复制按钮把译文写进剪贴板。
 * [warning] 非空时在正文末尾追加一行提示(如「原样度」警告),但不进剪贴板 —— 复制出去的是纯译文。
 */
internal fun showTranslatedDialog(context: Context, message: String, warning: String? = null) {
    val body = if (warning.isNullOrBlank()) message else message + "\n\n" + warning
    WitDialog.MessageDialogBuilder(context)
        .setTitle(context.getString(R.string.string_translate_caption))
        .setMessage(body)
        .addAction(context.getString(R.string.string_120)) { dialog, _ ->
            ClipBoardUtils.putTextIntoClipboard(context, message)
            dialog.dismiss()
        }
        .addAction(context.getString(R.string.sure)) { dialog, _ -> dialog.dismiss() }
        .show()
}

/**
 * 命中「疑似原样输出」时给出提示文案,否则 null。
 * 仅 AI 引擎([isAiBacked])、目标语言不是日文(同语言翻译允许保留原文)、且原样度达阈值时才提示。
 */
internal fun verbatimWarningText(
    context: Context,
    translator: Translator,
    original: String,
    translated: String,
    targetLang: String = appTranslateTargetLang(),
): String? {
    if (!translator.isAiBacked()) return null
    if (targetLang.equals("ja", ignoreCase = true)) return null
    if (!VerbatimRatio.isLikelyVerbatim(original, translated)) return null
    return context.getString(
        R.string.ai_translate_verbatim_warning,
        VerbatimRatio.percent(original, translated),
    )
}
