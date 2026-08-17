package ceui.pixiv.ui.translate

import android.content.Context
import ceui.lisa.R
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 详情页标题/简介翻译与评论翻译共享的「思考中」阶段提示与译文弹窗。
 *
 * 两处翻译入口(见 [ceui.pixiv.ui.detail.translateTitleAndCaption] 与
 * [ceui.pixiv.ui.comments.translateComment])此前各自复制了一份几乎相同的 QMUIDialog 装配
 * 与 THINKING 阶段 toast,这里收拢成共享成员,避免后续改一处漏一处。
 */
internal val onThinkingPhase: (AiTranslatePhase) -> Unit = { phase ->
    if (phase == AiTranslatePhase.THINKING) {
        Common.showToast(R.string.ai_translate_thinking)
    }
}

/**
 * 同一次操作里并发跑多条翻译时用这个:THINKING 只提示一次,不会几条请求各弹一个「思考中」。
 * 回调来自 IO 线程(见 [AiTranslator] 的流式解析),所以用 [AtomicBoolean] 而不是裸 var。
 */
internal fun onceThinkingPhase(): (AiTranslatePhase) -> Unit {
    val shown = AtomicBoolean(false)
    return { phase ->
        if (phase == AiTranslatePhase.THINKING && shown.compareAndSet(false, true)) {
            Common.showToast(R.string.ai_translate_thinking)
        }
    }
}

/** 弹出译文弹窗(挂 SkinManager 跟随日夜皮肤),复制按钮把整段译文写进剪贴板。 */
internal fun showTranslatedDialog(context: Context, message: String) {
    QMUIDialog.MessageDialogBuilder(context)
        .setTitle(context.getString(R.string.string_translate_caption))
        .setMessage(message)
        .setSkinManager(QMUISkinManager.defaultInstance(context))
        .addAction(context.getString(R.string.string_120)) { dialog, _ ->
            ClipBoardUtils.putTextIntoClipboard(context, message)
            dialog.dismiss()
        }
        .addAction(context.getString(R.string.sure)) { dialog, _ -> dialog.dismiss() }
        .show()
}
