package ceui.pixiv.ui.detail

import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.translate.appTranslateTargetLang
import ceui.pixiv.ui.translate.currentTranslator
import ceui.pixiv.ui.translate.onThinkingPhase
import ceui.pixiv.ui.translate.promptTranslateFailedIfPossible
import ceui.pixiv.ui.translate.showTranslatedDialog
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** 标题译文与简介译文之间的分隔符。 */
private const val TRANSLATED_MESSAGE_SEPARATOR = "\n\n"

/**
 * 简介标题栏「翻译」:同时翻译标题与简介,译成 app 内语言(见 [appTranslateTargetLang])。
 * 复用 [currentTranslator](自定义 AI 优先,否则内置 Google)与 [promptTranslateFailedIfPossible]
 * 的失败提示;译文按「标题：… \n\n 简介：…」弹 QMUIDialog 展示、可一键复制,交互与评论翻译
 * [ceui.pixiv.ui.comments.translateComment] 保持一致。标题/简介为空时显示占位 [no_info],不触发翻译。
 */
fun Fragment.translateTitleAndCaption(title: String?, caption: String?) {
    val t = title?.trim().orEmpty()
    val c = caption?.trim().orEmpty()
    if (t.isEmpty() && c.isEmpty()) return
    val ctx = requireContext()
    Common.showToast(R.string.string_translating)
    launchSuspend {
        // 只对非空字段发起批量翻译,一次取回两条(复用 translateBatch,避免两条顺序 translate)。
        val inputs = listOfNotNull(
            t.takeIf { it.isNotEmpty() },
            c.takeIf { it.isNotEmpty() },
        )
        val results = try {
            currentTranslator().translateBatch(
                inputs, appTranslateTargetLang(), onPhase = onThinkingPhase
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "translate title/caption failed")
            promptTranslateFailedIfPossible(e)
            return@launchSuspend
        }
        // 输入非空但译文空白 = 翻译失败:全部失败则不弹窗(与评论翻译一致);部分失败则失败字段用占位。
        if (results.all { it.isBlank() }) {
            promptTranslateFailedIfPossible(null)
            return@launchSuspend
        }
        val placeholder = ctx.getString(R.string.no_info)
        var idx = 0
        val titleText = if (t.isEmpty()) placeholder
            else results[idx++].takeIf { it.isNotBlank() } ?: placeholder
        val captionText = if (c.isEmpty()) placeholder
            else results[idx].takeIf { it.isNotBlank() } ?: placeholder
        val message = ctx.getString(R.string.string_182) + titleText + TRANSLATED_MESSAGE_SEPARATOR +
            ctx.getString(R.string.v3_translate_caption_label) + captionText
        showTranslatedDialog(ctx, message)
    }
}
