package ceui.pixiv.ui.novel.reader.tts

import ceui.pixiv.ui.novel.reader.model.ContentToken
import java.util.Locale

/**
 * Converts reader tokens to speech-safe text and keeps utterances below the
 * platform TTS limit. This is pure Kotlin so the boundary behaviour can be
 * tested without an Android device.
 */
object NovelTtsText {

    /** Android engines commonly reject utterances above roughly 4,000 chars. */
    const val DEFAULT_MAX_CHARS = 3_500
    /** Japanese voices sound more natural when the engine receives shorter turns. */
    const val JAPANESE_MAX_CHARS = 1_800

    data class Segment(
        val text: String,
        val sourceStart: Int = -1,
        val sourceEnd: Int = sourceStart + text.length,
        val wholeToken: Boolean = false,
    ) {
        /** Offsets use the same coordinate space as the reader's text blocks. */
        fun sourceRange(start: Int = 0, end: Int = text.length): IntRange? {
            if (sourceStart < 0 || start !in 0 until text.length || end !in (start + 1)..text.length) return null
            return if (wholeToken) sourceStart until sourceEnd
            else (sourceStart + start) until (sourceStart + end)
        }
    }

    /** Keep paragraph boundaries; split only to respect the engine input limit. */
    fun segmentsFromTokens(
        tokens: List<ContentToken>,
        startCharIndex: Int = 0,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<Segment> = buildList {
        for (token in tokens) {
            if (token.sourceEnd <= startCharIndex) continue
            when (token) {
                is ContentToken.Paragraph -> {
                    val offset = (startCharIndex - token.textSourceStart).coerceIn(0, token.text.length)
                    addAll(split(token.text.substring(offset), maxChars).map {
                        it.copy(sourceStart = token.textSourceStart + offset + it.sourceStart,
                            sourceEnd = token.textSourceStart + offset + it.sourceEnd)
                    })
                }
                is ContentToken.Chapter -> addAll(split(token.title, maxChars).map {
                    it.copy(sourceStart = token.sourceStart, sourceEnd = token.sourceEnd, wholeToken = true)
                })
                else -> Unit
            }
        }
    }

    fun paragraphStart(tokens: List<ContentToken>, charIndex: Int): Int? =
        tokens.firstOrNull { token ->
            when (token) {
                is ContentToken.Paragraph -> charIndex in token.textSourceStart until (token.textSourceStart + token.text.length)
                is ContentToken.Chapter -> charIndex in token.sourceStart until token.sourceEnd
                else -> false
            }
        }?.let { if (it is ContentToken.Paragraph) it.textSourceStart else it.sourceStart }

    /** Selects a speech locale from the script instead of the app/device UI locale. */
    fun detectLocale(text: String, fallback: Locale = Locale.getDefault()): Locale {
        return if (text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }) {
            Locale.JAPAN
        } else {
            fallback
        }
    }

    fun maxCharsFor(locale: Locale): Int =
        if (locale.language == Locale.JAPANESE.language) JAPANESE_MAX_CHARS else DEFAULT_MAX_CHARS

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
        val source = text
        val result = ArrayList<Segment>((source.length / maxChars) + 1)
        var start = 0
        while (start < source.length) {
            while (start < source.length && source[start].isWhitespace()) start++
            if (start == source.length) break
            val remaining = source.length - start
            if (remaining <= maxChars) {
                val part = source.substring(start).trimEnd()
                result += Segment(part, start, start + part.length)
                break
            }
            val hardEnd = start + maxChars
            val candidate = source.substring(start, hardEnd).indexOfLast { it in SENTENCE_BOUNDARIES }
            var boundary = if (candidate >= maxChars / 2) start + candidate + 1 else hardEnd
            // Do not send half of a supplementary character to the engine.
            if (boundary > start + 1 && source[boundary - 1].isHighSurrogate() && source[boundary].isLowSurrogate()) boundary--
            val part = source.substring(start, boundary).trimEnd()
            if (part.isNotEmpty()) result += Segment(part, start, start + part.length)
            start = boundary
            while (start < source.length && source[start].isWhitespace()) start++
        }
        return result
    }

    private val SENTENCE_BOUNDARIES = setOf(
        '\n', '。', '！', '？', '；', '：', '!', '?', ';', ':', '.', ',', '，',
    )
}
