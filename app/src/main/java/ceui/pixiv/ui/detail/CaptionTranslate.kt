package ceui.pixiv.ui.detail

import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.translate.appTranslateTargetLang
import ceui.pixiv.ui.translate.currentTranslator
import ceui.pixiv.ui.translate.onceThinkingPhase
import ceui.pixiv.ui.translate.promptTranslateFailedIfPossible
import ceui.pixiv.ui.translate.showTranslatedDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/** 标题译文与简介译文之间的分隔符。 */
private const val TRANSLATED_MESSAGE_SEPARATOR = "\n\n"

/**
 * 简介标题栏「翻译」:同时翻译标题与简介,译成 app 内语言(见 [appTranslateTargetLang])。
 * 复用 [currentTranslator](自定义 AI 优先,否则内置 Google)与 [promptTranslateFailedIfPossible]
 * 的失败提示;译文按「标题：… \n\n 简介：…」弹 QMUIDialog 展示、可一键复制,交互与评论翻译
 * [ceui.pixiv.ui.comments.translateComment] 保持一致。某一条为空或翻译失败时,那一行显示占位
 * [R.string.no_info];两条都没译出来才算整体失败,走统一的失败提示、不弹译文窗。
 *
 * ⚠️ 这里刻意**不走** `translateBatch`:那个批量接口是给 OCR 的短行设计的,Google 实现把多条
 * 用 `\n` join 成一次 POST、再按 `\n` 切回。简介是含换行的自由文本(pixiv 的 `<br>` 经
 * HtmlCompat 变成 `\n`),切分数量必然对不上——先白发一次带全文的 POST,再退化成逐条**串行**
 * 请求,反而更慢;万一换行被 Google 归并到恰好两行,标题和简介的译文还会错位拼在一起。
 * 只有两条,直接各翻各的、并发发出即可。
 */
fun Fragment.translateTitleAndCaption(title: String?, caption: String?) {
    val t = title?.trim().orEmpty()
    val c = caption?.trim().orEmpty()
    if (t.isEmpty() && c.isEmpty()) return
    val ctx = requireContext()
    Common.showToast(R.string.string_translating)
    launchSuspend {
        val translator = currentTranslator()
        val targetLang = appTranslateTargetLang()
        val onPhase = onceThinkingPhase()
        // 单条失败不连累另一条:失败的那条留空串,留到最后统一判定。
        val failure = AtomicReference<Exception?>(null)
        suspend fun translateOne(text: String): String {
            if (text.isEmpty()) return ""
            return try {
                translator.translate(text, targetLang, onPhase = onPhase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "translate title/caption failed")
                failure.compareAndSet(null, e)
                ""
            }
        }
        val (translatedTitle, translatedCaption) = coroutineScope {
            val titleTask = async { translateOne(t) }
            val captionTask = async { translateOne(c) }
            titleTask.await() to captionTask.await()
        }
        // 两条都没译出来 = 整体失败:不弹译文窗,交给统一的失败提示(与评论翻译一致)。
        if (translatedTitle.isBlank() && translatedCaption.isBlank()) {
            promptTranslateFailedIfPossible(failure.get())
            return@launchSuspend
        }
        val placeholder = ctx.getString(R.string.no_info)
        val message = ctx.getString(R.string.string_182) +
            (translatedTitle.takeIf { it.isNotBlank() } ?: placeholder) +
            TRANSLATED_MESSAGE_SEPARATOR +
            ctx.getString(R.string.v3_translate_caption_label) +
            (translatedCaption.takeIf { it.isNotBlank() } ?: placeholder)
        showTranslatedDialog(ctx, message)
    }
}
