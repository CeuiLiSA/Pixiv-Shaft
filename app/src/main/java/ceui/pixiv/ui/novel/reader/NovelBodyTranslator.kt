package ceui.pixiv.ui.novel.reader

import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.translate.AiTranslator
import ceui.pixiv.ui.translate.GoogleWebTranslator
import ceui.pixiv.ui.translate.Translator
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 小说正文全文翻译（#864、#1373）：启用自定义 AI 时复用用户接口，否则保留 Google
 * 免费端点，不占云翻译额度。AI 用 JSON 数组保住段落边界，不以换行拆分模型输出。
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
        translator: Translator,
        onChunk: (Map<Int, String>) -> Unit,
    ) {
        val texts = paragraphs.map { it.text }
        for ((from, to) in AiTranslator.chunkByCharLimit(texts, MAX_CHUNK_CHARS)) {
            coroutineContext.ensureActive()
            val slice = texts.subList(from, to)
            val translated = if (translator === GoogleWebTranslator) {
                val lines = slice.map { it.replace(JOIN_SEP, " ") }
                val reply = translator.translate(lines.joinToString(JOIN_SEP), targetLang).split(JOIN_SEP)
                // 行数对不上就逐段重翻，避免译文错位；网络失败仍直接上抛，不逐段重试。
                if (reply.size == lines.size) reply else lines.map { translator.translate(it, targetLang) }
            } else {
                translator.translateBatch(slice, targetLang)
            }
            coroutineContext.ensureActive()
            if (translated.size != slice.size) throw IOException("Novel translation paragraph count mismatch")
            val chunk = (from until to).mapNotNull { index ->
                translated[index - from].takeIf { it.isNotBlank() }?.let { paragraphs[index].sourceStart to it }
            }.toMap()
            if (chunk.isNotEmpty()) onChunk(chunk)
            // AI 批量接口允许部分成功。保留成功段落，失败段落下次重试，不能把空串记为已完成。
            if (chunk.size != slice.size) throw IOException("Novel translation returned empty paragraphs")
        }
    }
}
