package ceui.pixiv.ui.novel.reader.paginate

import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.TranslationRole

/**
 * 把全文翻译的译文以 [ContentToken.Paragraph] 的形态放进展示 token 流（issue #864）：
 * [TranslationRole.Interlinear] 插到各自原文段落正下方（双语对照），
 * [TranslationRole.Replacement] 顶替原文段落（仅译文）。
 *
 * 和 [IllustMixInserter] 同一套约定：纯函数、只在展示链路上调用，原始 tokens
 * （导出 / 复制 / 朗读 / 进程缓存）不被污染，anchor 保持单调。复用 Paragraph 而不是
 * 新造 token 类型，横向 Paginator / 纵向 ScrollReader 的段落渲染链路照旧生效。
 */
object TranslationInserter {

    /**
     * @param translations 原文段落 [ContentToken.Paragraph.sourceStart] → 译文。
     *   没有译文（还没翻到 / 失败 / 译文为空）的段落原样保留。
     * @param role 译文的身份，不能是 [TranslationRole.None]。
     */
    fun insert(
        tokens: List<ContentToken>,
        translations: Map<Int, String>,
        role: TranslationRole,
    ): List<ContentToken> {
        require(role != TranslationRole.None) { "translation role must not be None" }
        if (tokens.isEmpty() || translations.isEmpty()) return tokens
        val result = ArrayList<ContentToken>(tokens.size + translations.size)
        for (token in tokens) {
            val translated = (token as? ContentToken.Paragraph)
                ?.takeIf { !it.isTranslation }
                ?.let { translations[it.sourceStart]?.trim() }
            if (translated.isNullOrEmpty()) {
                result += token
                continue
            }
            token as ContentToken.Paragraph
            if (role == TranslationRole.Replacement) {
                result += ContentToken.Paragraph(
                    sourceStart = token.sourceStart,
                    sourceEnd = token.sourceEnd,
                    text = translated,
                    textSourceStart = token.textSourceStart,
                    translationRole = role,
                )
            } else {
                result += token
                result += ContentToken.Paragraph(
                    sourceStart = token.sourceEnd,
                    sourceEnd = token.sourceEnd,
                    text = translated,
                    textSourceStart = token.sourceEnd,
                    translationRole = role,
                )
            }
        }
        return result
    }
}
