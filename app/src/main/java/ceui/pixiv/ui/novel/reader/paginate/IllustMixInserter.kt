package ceui.pixiv.ui.novel.reader.paginate

import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * 把「自动混排插画」以 [ContentToken.PixivImage] 的形态插进 token 流（issue #999）。
 *
 * 纯函数、只在展示链路上调用：原始 tokens（导出 / 复制 / 章节大纲 / 进程缓存）不被污染。
 * 插入的 token 取**零宽度**源偏移（sourceStart == sourceEnd == 前一段落的 sourceEnd），
 * 这样既保持 anchor 单调不破坏进度定位，也不会把阅读进度算进任何虚构的字符区间。
 * 复用 PixivImage 而不是新造 token 类型，横向 Paginator / 纵向 ScrollReader 的渲染、
 * 点击跳详情全部照旧生效。
 */
object IllustMixInserter {

    /**
     * 每隔多少个段落插一张。固定默认值，需要再配置化时提成 ReaderSettings。
     * 网文段落普遍很短（对话体一两行一段），40 段在真机上约合每 4-5 个横向页一张，
     * 25 段则密到每 2-3 页一张、喧宾夺主（Pixel 8 实测）。
     */
    const val DEFAULT_INTERVAL_PARAGRAPHS = 40

    /**
     * @param illustIds 候选插画 id，**顺序即消费顺序**——调用方先过
     *   [ceui.pixiv.ui.novel.reader.IllustMixRanker] 排好相关性（内含按 novelId
     *   的稳定洗牌，重排版/转屏不会让插图跳来跳去）；已内嵌在正文里的
     *   `[pixivimage:]` 同 id 会被跳过。
     */
    fun insert(
        tokens: List<ContentToken>,
        illustIds: List<Long>,
        intervalParagraphs: Int = DEFAULT_INTERVAL_PARAGRAPHS,
    ): List<ContentToken> {
        if (tokens.isEmpty() || illustIds.isEmpty() || intervalParagraphs <= 0) return tokens
        val embedded = tokens.filterIsInstance<ContentToken.PixivImage>().mapTo(HashSet()) { it.illustId }
        val queue = ArrayDeque(illustIds.filter { it > 0 && it !in embedded }.distinct())
        if (queue.isEmpty()) return tokens

        val result = ArrayList<ContentToken>(tokens.size + queue.size)
        var paragraphsSinceInsert = 0
        for (token in tokens) {
            result += token
            if (token is ContentToken.Paragraph && queue.isNotEmpty()) {
                paragraphsSinceInsert++
                if (paragraphsSinceInsert >= intervalParagraphs) {
                    result += ContentToken.PixivImage(
                        sourceStart = token.sourceEnd,
                        sourceEnd = token.sourceEnd,
                        illustId = queue.removeFirst(),
                        pageIndex = 0,
                        isMix = true,
                    )
                    paragraphsSinceInsert = 0
                }
            }
        }
        return result
    }
}
