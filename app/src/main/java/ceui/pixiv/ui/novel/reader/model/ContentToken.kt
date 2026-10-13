package ceui.pixiv.ui.novel.reader.model

/** 全文翻译的译文段落在展示 token 流里的身份（issue #864）。 */
enum class TranslationRole {
    /** 原文段落。 */
    None,

    /**
     * 双语对照：插在原文段落正下方，字号降一级、次要文字色。源偏移**零宽度**
     * （sourceStart == sourceEnd == textSourceStart == 原文段落的 sourceEnd）。
     */
    Interlinear,

    /**
     * 仅译文：顶替原文段落，按正文样式排版。源区间沿用原文段落，进度锚点照常落在段首。
     */
    Replacement,
}

sealed class ContentToken {
    abstract val sourceStart: Int
    abstract val sourceEnd: Int

    data class Paragraph(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val text: String,
        /** Position in the raw source where [text] actually begins.
         *  May differ from [sourceStart] when leading whitespace was trimmed. */
        val textSourceStart: Int = sourceStart,
        /** Inline markup spans (links, ruby, etc.) with offsets into [text]. */
        val inlineSpans: List<ceui.pixiv.ui.novel.reader.paginate.InlineSpan> = emptyList(),
        /**
         * 全文翻译的译文段落（issue #864），只出现在展示链路里，见 [TranslationRole]。
         * [text] 不在源文本中：选区 / 高亮把整段映射到 [textSourceStart] 这一个点。
         */
        val translationRole: TranslationRole = TranslationRole.None,
    ) : ContentToken() {
        val isTranslation: Boolean get() = translationRole != TranslationRole.None
    }

    data class Chapter(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val title: String,
    ) : ContentToken()

    data class PixivImage(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val illustId: Long,
        val pageIndex: Int,
        /** true = 「自动混排插画」插入的图（issue #999），渲染时带圆角；正文内嵌 `[pixivimage:]` 为 false。 */
        val isMix: Boolean = false,
    ) : ContentToken()

    data class UploadedImage(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val imageId: Long,
    ) : ContentToken()

    data class PageBreak(
        override val sourceStart: Int,
        override val sourceEnd: Int,
    ) : ContentToken()

    data class BlankLine(
        override val sourceStart: Int,
        override val sourceEnd: Int,
    ) : ContentToken()

    /**
     * `[jump:N]` — Pixiv novel inter-page navigation. `target` is the
     * 1-indexed `[newpage]`-delimited segment to jump to. Renders as a
     * tappable button on its own row. Emitted both for own-line jumps and
     * for inline jumps split out of a paragraph (CYOA-style option lines).
     */
    data class Jump(
        override val sourceStart: Int,
        override val sourceEnd: Int,
        val target: Int,
    ) : ContentToken()
}
