package ceui.pixiv.ui.novel.reader.paginate

import ceui.pixiv.ui.novel.reader.model.ContentToken
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户反馈 bug：导出 TXT 后章节标题里数字两侧出现多余引号，例如：
 *   错误：【第'0章'】
 *   正确：【第0章】
 *
 * 此处用真实/常见的章节文本固定 cleaner 行为，确保 ASCII / 弯引号 / 双引号都被剥除，
 * 同时不破坏正常带文字的标题。
 */
class ContentParserChapterTitleTest {

    @Test fun `straight single quotes around digits are stripped`() {
        assertEquals("第0章", ContentParser.cleanChapterTitle("第'0章'"))
    }

    @Test fun `curly single quotes around digits are stripped`() {
        assertEquals("第12章", ContentParser.cleanChapterTitle("第‘12’章’"))
    }

    @Test fun `double quotes are preserved as legitimate punctuation`() {
        // 双引号在章节标题里是合法的引语标点，不剥除；只针对单引号 bug。
        assertEquals("Chapter \"7\"", ContentParser.cleanChapterTitle("Chapter \"7\""))
    }

    @Test fun `mixed straight and curly are all stripped`() {
        assertEquals("第3章 终", ContentParser.cleanChapterTitle("第'3’章 终"))
    }

    @Test fun `plain title without quotes is preserved`() {
        assertEquals("序章", ContentParser.cleanChapterTitle("序章"))
    }

    @Test fun `cjk corner brackets are preserved`() {
        assertEquals("「序章」", ContentParser.cleanChapterTitle("「序章」"))
    }

    @Test fun `apostrophe in non numeric title is preserved`() {
        // 标题不含数字时，单引号被认为是合法标点，原样保留。
        assertEquals("It's me", ContentParser.cleanChapterTitle("It's me"))
    }

    @Test fun `tokenize emits cleaned chapter title for buggy input`() {
        val text = "[chapter:第'0章']\n正文 line"
        val tokens = ContentParser.tokenize(text)
        val chapter = tokens.filterIsInstance<ContentToken.Chapter>().single()
        assertEquals("第0章", chapter.title)
    }
}
