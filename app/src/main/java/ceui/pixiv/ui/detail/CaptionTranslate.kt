package ceui.pixiv.ui.detail

import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.translate.AiTranslatePhase
import ceui.pixiv.ui.translate.appTranslateTargetLang
import ceui.pixiv.ui.translate.currentTranslator
import ceui.pixiv.ui.translate.promptTranslateFailedIfPossible
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import kotlinx.coroutines.CancellationException
import timber.log.Timber

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
        val translated = try {
            val translatedTitle = if (t.isEmpty()) null else currentTranslator().translate(
                t, appTranslateTargetLang()
            ) { phase ->
                if (phase == AiTranslatePhase.THINKING) {
                    Common.showToast(R.string.ai_translate_thinking)
                }
            }
            val translatedCaption = if (c.isEmpty()) null else currentTranslator().translate(
                c, appTranslateTargetLang()
            ) { phase ->
                if (phase == AiTranslatePhase.THINKING) {
                    Common.showToast(R.string.ai_translate_thinking)
                }
            }
            translatedTitle to translatedCaption
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "translate title/caption failed")
            promptTranslateFailedIfPossible(e)
            return@launchSuspend
        }
        val (translatedTitle, translatedCaption) = translated
        val placeholder = ctx.getString(R.string.no_info)
        val titleText = translatedTitle?.takeIf { it.isNotBlank() } ?: placeholder
        val captionText = translatedCaption?.takeIf { it.isNotBlank() } ?: placeholder
        val message = ctx.getString(R.string.string_182) + titleText + "\n\n" +
            ctx.getString(R.string.v3_translate_caption_label) + captionText
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.string_translate_caption))
            .setMessage(message)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(ctx.getString(R.string.string_120)) { dialog, _ ->
                ClipBoardUtils.putTextIntoClipboard(ctx, message)
                dialog.dismiss()
            }
            .addAction(ctx.getString(R.string.sure)) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
