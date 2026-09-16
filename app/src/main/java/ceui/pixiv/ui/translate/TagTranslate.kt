package ceui.pixiv.ui.translate

import android.content.Context
import ceui.lisa.R
import ceui.lisa.utils.Common
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 长按标签「翻译」(#1054):标签冷门没译名、或 pixiv 只给了英文译名时,拿**原文**直接译成
 * app 内语言(见 [appTranslateTargetLang])。引擎 / 弹窗 / 失败提示与评论翻译
 * [ceui.pixiv.ui.comments.translateComment] 完全同一套。
 *
 * 入口既有 Fragment(V2 [ceui.lisa.fragments.FragmentIllust])也有裸 View
 * ([ceui.pixiv.widgets.V3TagFlowView] 的长按菜单),所以协程作用域由调用方给,
 * 不绑 Fragment 扩展。
 */
fun translateTag(context: Context, scope: CoroutineScope, name: String) {
    val src = name.trim()
    if (src.isEmpty()) return
    Common.showToast(R.string.string_translating)
    scope.launch {
        val translated = try {
            currentTranslator().translate(src, appTranslateTargetLang(), onPhase = onceThinkingPhase())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "translate tag failed")
            promptTranslateFailedIfPossible(e)
            return@launch
        }
        if (translated.isBlank()) {
            promptTranslateFailedIfPossible(null)
            return@launch
        }
        showTranslatedDialog(context, translated)
    }
}
