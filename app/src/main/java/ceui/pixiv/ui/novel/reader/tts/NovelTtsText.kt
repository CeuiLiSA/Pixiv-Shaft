package ceui.pixiv.ui.novel.reader.tts

import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * Converts reader tokens to speech-safe text and keeps utterances below the
 * platform TTS limit. This is pure Kotlin so the boundary behaviour can be
 * tested without an Android device.
 */
object NovelTtsText {

    /** Android engines commonly reject utterances above roughly 4,000 chars. */
    const val DEFAULT_MAX_CHARS = 3_500

    data class Segment(val text: String)

    fun fromTokens(
        tokens: List<ContentToken>,
        title: String? = null,
        startCharIndex: Int = 0,
    ): String {
        return buildString {
            if (!title.isNullOrBlank() && startCharIndex <= 0) {
                append(title.trim())
                append('\n')
            }
            for (token in tokens) {
                if (token.sourceEnd <= startCharIndex) continue
                when (token) {
                    is ContentToken.Paragraph -> {
                        val offset = (startCharIndex - token.textSourceStart)
                            .coerceIn(0, token.text.length)
                        append(token.text.substring(offset)).append('\n')
                    }
                    is ContentToken.Chapter -> append(token.title).append('\n')
                    is ContentToken.BlankLine,
                    is ContentToken.PageBreak,
                    -> append('\n')
                    // Images and navigation controls have no useful spoken
                    // representation; skipping them avoids reading markup.
                    is ContentToken.PixivImage,
                    is ContentToken.UploadedImage,
                    is ContentToken.Jump,
                    -> Unit
                }
            }
        }.trim()
    }

    fun split(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<Segment> {
        require(maxChars > 0) { "maxChars must be positive" }
        val source = text.trim()
        if (source.isEmpty()) return emptyList()
        val result = ArrayList<Segment>((source.length / maxChars) + 1)
        var start = 0
        while (start < source.length) {
            val remaining = source.length - start
            if (remaining <= maxChars) {
                result += Segment(source.substring(start).trim())
                break
            }
            val hardEnd = start + maxChars
            val candidate = source.substring(start, hardEnd).indexOfLast { it in SENTENCE_BOUNDARIES }
            val boundary = if (candidate >= maxChars / 2) start + candidate + 1 else hardEnd
            val part = source.substring(start, boundary).trim()
            if (part.isNotEmpty()) result += Segment(part)
            start = boundary
            while (start < source.length && source[start].isWhitespace()) start++
        }
        return result
    }

    private val SENTENCE_BOUNDARIES = setOf(
        '\n', '。', '！', '？', '；', '：', '!', '?', ';', ':', '.', ',', '，',
    )
}
