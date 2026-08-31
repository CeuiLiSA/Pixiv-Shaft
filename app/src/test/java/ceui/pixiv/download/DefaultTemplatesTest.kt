package ceui.pixiv.download

import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.sanitize.FsSanitizer
import ceui.pixiv.download.template.DefaultTemplates
import ceui.pixiv.download.template.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DefaultTemplatesTest {

    private val META = ItemMeta(
        id = 12345L,
        title = "作品タイトル",
        author = Author(id = 999L, name = "作者"),
        createdAt = Instant.parse("2024-08-15T09:32:10Z"),
        page = 0,
        totalPages = 1,
        flags = emptySet(),
    )

    @Test fun `every bucket has a compilable default template`() {
        Bucket.entries.forEach { bucket ->
            val src = DefaultTemplates.SOURCES.getValue(bucket)
            val compiled = Template.compile(src)
            val ext = when (bucket) {
                Bucket.Illust, Bucket.TempCache -> "jpg"
                Bucket.Ugoira -> "gif"
                Bucket.Novel, Bucket.NovelSeries, Bucket.Caption, Bucket.Log -> "txt"
                Bucket.Backup -> "json"
            }
            val rendered = compiled.render(META, ext)
            val cleaned = FsSanitizer.clean(rendered)
            assertTrue("Bucket $bucket produced empty path", cleaned.segments.isNotEmpty())
        }
    }

    @Test fun `illust legacy default keeps r18 and ai in the shared folder`() {
        val t = Template.compile(DefaultTemplates.ILLUST)
        val plain = t.render(META.copy(flags = emptySet()), "jpg").joinTo()
        assertTrue("no R18 segment", !plain.contains("R18"))
        assertTrue("no AI segment", !plain.contains("/AI/"))

        val both = t.render(META.copy(flags = setOf(Flag.R18, Flag.AI)), "jpg").joinTo()
        assertEquals("flags do not change the legacy default path", plain, both)
    }

    @Test fun `backup default template uses ShaftBackups and json filename`() {
        assertTrue(DefaultTemplates.BACKUP.startsWith("ShaftBackups/{title}_"))
        assertTrue(DefaultTemplates.BACKUP.endsWith(".json"))
    }

    @Test fun `illust default appends one based page number only for multi-page`() {
        val t = Template.compile(DefaultTemplates.ILLUST)
        val single = t.render(META.copy(totalPages = 1, page = 0), "jpg").filename
        assertEquals("作品タイトル_12345.jpg", single)

        val multi = t.render(META.copy(totalPages = 3, page = 2), "jpg").filename
        assertEquals("作品タイトル_12345_p3.jpg", multi)
    }

    @Test fun `compileAll produces one template per bucket`() {
        val compiled = DefaultTemplates.compileAll()
        assertEquals(Bucket.entries.size, compiled.size)
        Bucket.entries.forEach { assertTrue(it in compiled) }
    }
}
