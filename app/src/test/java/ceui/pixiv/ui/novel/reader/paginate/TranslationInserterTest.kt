package ceui.pixiv.ui.novel.reader.paginate

import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.TranslationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #864：小说全文翻译。双语对照的译文紧贴各自原文段落之后、零宽度源偏移；
 * 仅译文顶替原文段落、沿用其源区间。anchor 单调，没有译文的段落原样保留。
 */
class TranslationInserterTest {

    private fun paragraphs(count: Int, lengthEach: Int = 10): List<ContentToken.Paragraph> {
        var offset = 0
        return List(count) {
            val p = ContentToken.Paragraph(
                sourceStart = offset,
                sourceEnd = offset + lengthEach,
                text = "段落$it",
            )
            offset += lengthEach + 1
            p
        }
    }

    @Test fun `no translations returns original tokens untouched`() {
        val tokens = paragraphs(5)
        assertSame(tokens, TranslationInserter.insert(tokens, emptyMap(), TranslationRole.Interlinear))
    }

    @Test fun `translation follows its own paragraph with zero-width source offsets`() {
        val tokens = paragraphs(3)
        val out = TranslationInserter.insert(tokens, mapOf(tokens[1].sourceStart to "译文1"), TranslationRole.Interlinear)
        assertEquals(4, out.size)
        assertSame(tokens[1], out[1])
        val translation = out[2] as ContentToken.Paragraph
        assertEquals(TranslationRole.Interlinear, translation.translationRole)
        assertEquals("译文1", translation.text)
        assertEquals(tokens[1].sourceEnd, translation.sourceStart)
        assertEquals(tokens[1].sourceEnd, translation.sourceEnd)
        assertEquals(tokens[1].sourceEnd, translation.textSourceStart)
        assertSame(tokens[2], out[3])
    }

    @Test fun `blank translations are skipped and anchors stay monotonic`() {
        val tokens = paragraphs(4)
        val out = TranslationInserter.insert(
            tokens,
            tokens.associate { it.sourceStart to if (it.sourceStart == tokens[2].sourceStart) "  " else "译${it.text}" },
            TranslationRole.Interlinear,
        )
        assertEquals(7, out.size)
        val anchors = out.map { it.sourceStart }
        assertEquals(anchors.sorted(), anchors)
    }

    @Test fun `translation lands before an illust mix image that follows the paragraph`() {
        val tokens = paragraphs(2)
        val mixed = IllustMixInserter.insert(tokens, listOf(7L), intervalParagraphs = 1)
        val out = TranslationInserter.insert(mixed, mapOf(tokens[0].sourceStart to "译文0"), TranslationRole.Interlinear)
        assertTrue((out[1] as ContentToken.Paragraph).isTranslation)
        assertTrue(out[2] is ContentToken.PixivImage)
    }

    @Test fun `replacement swaps the paragraph text but keeps its source range`() {
        val tokens = paragraphs(3)
        val out = TranslationInserter.insert(tokens, mapOf(tokens[1].sourceStart to "译文1"), TranslationRole.Replacement)
        assertEquals(3, out.size)
        assertSame(tokens[0], out[0])
        val replaced = out[1] as ContentToken.Paragraph
        assertEquals(TranslationRole.Replacement, replaced.translationRole)
        assertEquals("译文1", replaced.text)
        assertEquals(tokens[1].sourceStart, replaced.sourceStart)
        assertEquals(tokens[1].sourceEnd, replaced.sourceEnd)
        assertEquals(tokens[1].textSourceStart, replaced.textSourceStart)
        assertSame(tokens[2], out[2])
    }
}
