package ceui.pixiv.download

import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.template.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class TemplateTest {

    private val META = ItemMeta(
        id = 42L,
        title = "Hello World",
        author = Author(id = 7L, name = "Alice"),
        createdAt = Instant.parse("2024-01-02T03:04:05Z"),
        page = 2,
        totalPages = 4,
        width = 800, height = 600,
        flags = setOf(Flag.R18, Flag.AI),
    )

    @Test fun `renders simple variables`() {
        val t = Template.compile("Shaft/{title} {id}.{ext}")
        val p = t.render(META, "png")
        assertEquals("Shaft/Hello World 42.png", p.joinTo())
    }

    @Test fun `scrubs path separators inside variable values`() {
        val dirty = META.copy(title = "evil/../..\\path")
        val t = Template.compile("dir/{title}.{ext}")
        val p = t.render(dirty, "png")
        assertEquals(listOf("dir", "evil_.._.._path.png"), p.segments)
    }

    @Test fun `conditional block renders when flag present`() {
        val t = Template.compile("[?R18:R18/]{title}.{ext}")
        val p = t.render(META, "png")
        assertEquals("R18/Hello World.png", p.joinTo())
    }

    @Test fun `conditional block hides when flag absent`() {
        val sfw = META.copy(flags = emptySet())
        val t = Template.compile("[?R18:R18/]{title}.{ext}")
        val p = t.render(sfw, "png")
        assertEquals("Hello World.png", p.joinTo())
    }

    @Test fun `negated flag condition`() {
        val t = Template.compile("[?!R18:safe/]{title}.{ext}")
        val sfw = META.copy(flags = emptySet())
        assertEquals("safe/Hello World.png", t.render(sfw, "png").joinTo())
        val nsfw = META
        assertEquals("Hello World.png", t.render(nsfw, "png").joinTo())
    }

    @Test fun `page greater than condition`() {
        val t = Template.compile("{title}[?p>1: p{page}].{ext}")
        assertEquals("Hello World p2.png", t.render(META, "png").joinTo())
        val single = META.copy(totalPages = 1)
        assertEquals("Hello World.png", t.render(single, "png").joinTo())
    }

    @Test fun `created date formatting`() {
        val t = Template.compile("{created:yyyy-MM-dd}.{ext}")
        val p = t.render(META, "png")
        assertEquals("2024-01-02.png", p.segments.single())
    }

    @Test fun `unknown variable fails`() {
        val t = Template.compile("{made_up}.{ext}")
        assertThrows(IllegalStateException::class.java) { t.render(META, "png") }
    }

    @Test fun `escaped braces are literal`() {
        val t = Template.compile("""{id}-\{raw\}.{ext}""")
        assertEquals("42-{raw}.png", t.render(META, "png").joinTo())
    }

    @Test fun `nested fields through conditionals`() {
        val t = Template.compile("[?AI:AI/][?R18:R18/]{author} ({author_id})/{title} {id}.{ext}")
        val p = t.render(META, "png")
        assertEquals("AI/R18/Alice (7)/Hello World 42.png", p.joinTo())
    }
}
