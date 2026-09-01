package ceui.pixiv.ui.comments

import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.launchSuspend
import ceui.pixiv.ui.translate.appTranslateTargetLang
import ceui.pixiv.ui.translate.currentTranslator
import ceui.pixiv.ui.translate.onThinkingPhase
import ceui.pixiv.ui.translate.promptTranslateFailedIfPossible
import ceui.pixiv.ui.translate.showTranslatedDialog
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 长按评论「翻译」:走 [currentTranslator](内置谷歌网页翻译,或用户配置的自定义 AI 引擎 #975),
 * 译成 **app 内语言**(见 [appTranslateTargetLang],不是写死中文);译文用 WitDialog 弹出、
 * 可一键复制;失败复用 [promptTranslateFailedIfPossible] 按引擎给出明确提示(谷歌 → 需要代理,
 * AI → 真实错误),别让用户当成 app 的 bug。弹窗挂 SkinManager 跟随日夜皮肤。
 */
fun Fragment.translateComment(text: String?) {
    val src = text?.trim().orEmpty()
    if (src.isEmpty()) return
    val ctx = requireContext()
    Common.showToast(R.string.string_translating)
    launchSuspend {
        val translated = try {
            currentTranslator().translate(src, appTranslateTargetLang(), onPhase = onThinkingPhase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "translate comment failed")
            promptTranslateFailedIfPossible(e)
            return@launchSuspend
        }
        if (translated.isBlank()) {
            promptTranslateFailedIfPossible(null)
            return@launchSuspend
        }
        showTranslatedDialog(ctx, translated)
    }
}
