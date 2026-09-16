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

/** 弹出译文弹窗(挂 SkinManager 跟随日夜皮肤),复制按钮把整段译文写进剪贴板。 */
internal fun showTranslatedDialog(context: Context, message: String) {
    WitDialog.MessageDialogBuilder(context)
        .setTitle(context.getString(R.string.string_translate_caption))
        .setMessage(message)
        .addAction(context.getString(R.string.string_120)) { dialog, _ ->
            ClipBoardUtils.putTextIntoClipboard(context, message)
            dialog.dismiss()
        }
        .addAction(context.getString(R.string.sure)) { dialog, _ -> dialog.dismiss() }
        .show()
}
