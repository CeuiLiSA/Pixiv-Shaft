package ceui.pixiv.ui.novel.reader.paginate

import ceui.pixiv.ui.novel.reader.model.ContentToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #999：小说正文自动混排插画。插入必须是纯展示层变换——零宽度源偏移、
 * anchor 单调、不动原始 token 序列本身；来源为空时原样返回，正文回退纯文字。
 */
class IllustMixInserterTest {

    private fun paragraphs(count: Int, lengthEach: Int = 10): List<ContentToken> {
        var offset = 0
        return List(count) {
            val p = ContentToken.Paragraph(
                sourceStart = offset,
                sourceEnd = offset + lengthEach,
                text = "段落$it",
            )
            offset += lengthEach
            p
        }
    }

    @Test fun `empty illust list returns original tokens untouched`() {
        val tokens = paragraphs(30)
        assertSame(tokens, IllustMixInserter.insert(tokens, emptyList(), 5))
    }

    @Test fun `inserts one image after every interval paragraphs`() {
        val tokens = paragraphs(10)
        val out = IllustMixInserter.insert(tokens, listOf(101L, 102L, 103L), intervalParagraphs = 3)
        val images = out.filterIsInstance<ContentToken.PixivImage>()
        assertEquals(3, images.size)
        // 第 3、6、9 个段落之后各一张
        assertTrue(out[3] is ContentToken.PixivImage)
        assertTrue(out[7] is ContentToken.PixivImage)
        assertTrue(out[11] is ContentToken.PixivImage)
    }

    @Test fun `inserted tokens are zero width at preceding paragraph end`() {
        val tokens = paragraphs(3)
        val out = IllustMixInserter.insert(tokens, listOf(7L), intervalParagraphs = 2)
        val image = out.filterIsInstance<ContentToken.PixivImage>().single()
        val prev = tokens[1]
        assertEquals(prev.sourceEnd, image.sourceStart)
        assertEquals(prev.sourceEnd, image.sourceEnd)
        // isMix 驱动渲染层圆角；内嵌 [pixivimage:] 保持 false
        assertTrue(image.isMix)
    }

    @Test fun `anchors stay monotonic after insertion`() {
        val tokens = paragraphs(50, lengthEach = 17)
        val out = IllustMixInserter.insert(tokens, (1L..20L).toList(), intervalParagraphs = 4)
        var last = -1
        for (t in out) {
            assertTrue(t.sourceStart >= last)
            last = t.sourceStart
        }
    }

    @Test fun `runs out of illusts gracefully`() {
        val tokens = paragraphs(100)
        val out = IllustMixInserter.insert(tokens, listOf(1L), intervalParagraphs = 2)
        assertEquals(1, out.filterIsInstance<ContentToken.PixivImage>().size)
    }

    @Test fun `consumes illust ids in the given order`() {
        // 顺序即相关性排序的结果（IllustMixRanker），插入器不得自己洗牌
        val tokens = paragraphs(9)
        val out = IllustMixInserter.insert(tokens, listOf(300L, 100L, 200L), intervalParagraphs = 3)
        assertEquals(
            listOf(300L, 100L, 200L),
            out.filterIsInstance<ContentToken.PixivImage>().map { it.illustId },
        )
    }

    @Test fun `skips illusts already embedded in the novel body`() {
        val tokens = paragraphs(6) + ContentToken.PixivImage(sourceStart = 60, sourceEnd = 80, illustId = 5L, pageIndex = 0)
        val out = IllustMixInserter.insert(tokens, listOf(5L), intervalParagraphs = 2)
        // 唯一候选与内嵌插图撞了 id → 不再插入
        assertEquals(1, out.filterIsInstance<ContentToken.PixivImage>().size)
    }

    @Test fun `non paragraph tokens do not advance the interval counter`() {
        val tokens = listOf(
            ContentToken.Paragraph(0, 10, "a"),
            ContentToken.BlankLine(10, 11),
            ContentToken.PageBreak(11, 12),
            ContentToken.Chapter(12, 20, "章"),
            ContentToken.Paragraph(20, 30, "b"),
        )
        val out = IllustMixInserter.insert(tokens, listOf(9L), intervalParagraphs = 2)
        val image = out.filterIsInstance<ContentToken.PixivImage>().single()
        // 第二个段落（"b"）之后才凑满 2 段
        assertEquals(30, image.sourceStart)
    }
}
