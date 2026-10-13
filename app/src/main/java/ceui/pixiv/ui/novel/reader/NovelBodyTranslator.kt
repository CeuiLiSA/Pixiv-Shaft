package ceui.pixiv.ui.novel.reader

import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.translate.AiTranslator
import ceui.pixiv.ui.translate.GoogleWebTranslator
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 小说正文全文翻译（issue #864）：固定走 Google 免费端点——R-18 正文不拒译、不吞句，
 * 不占云翻译额度，一万字三个请求、十秒内翻完（2026-10 实测）。
 *
 * 不复用 [GoogleWebTranslator.translateBatch]：它在一整片请求失败时退化为逐段重发，
 * 网络不通（国内没开代理）时每段都要等一次连接超时，一篇小说能卡十几分钟。这里任一片
 * 请求失败就整体抛出，已回调出去的译文由调用方保留，下次只补没翻到的段落。
 */
object NovelBodyTranslator {

    /** gtx 的 q 实际上限约 5000 字符，与 [GoogleWebTranslator] 同样留余量。 */
    private const val MAX_CHUNK_CHARS = 4000
    private const val JOIN_SEP = "\n"

    /**
     * 按 [paragraphs] 的顺序分片翻译；每片完成回调一次
     * （原文段落 [ContentToken.Paragraph.sourceStart] → 译文）。
     */
    suspend fun translate(
        paragraphs: List<ContentToken.Paragraph>,
        targetLang: String,
        onChunk: (Map<Int, String>) -> Unit,
    ) {
        val texts = paragraphs.map { it.text.replace(JOIN_SEP, " ") }
        for ((from, to) in AiTranslator.chunkByCharLimit(texts, MAX_CHUNK_CHARS)) {
            coroutineContext.ensureActive()
            val slice = texts.subList(from, to)
            val lines = GoogleWebTranslator.translate(slice.joinToString(JOIN_SEP), targetLang).split(JOIN_SEP)
            // 行数对不上（极少见）就把这一片逐段重翻，避免译文错位到别的段落下面。
            val translated = if (lines.size == slice.size) {
                lines
            } else {
                slice.map { GoogleWebTranslator.translate(it, targetLang) }
            }
            onChunk((from until to).associate { paragraphs[it].sourceStart to translated[it - from] })
        }
    }
}
