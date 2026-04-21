package ceui.pixiv.download

import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.template.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TemplateFormatTest {

    private val META = ItemMeta(
        id = 1L,
        title = "x",
        author = Author(2L, "y"),
        createdAt = Instant.parse("2024-08-15T09:32:10Z"),
    )

    @Test fun `default created format is compact timestamp`() {
        val t = Template.compile("{created}.{ext}")
        val name = t.render(META, "png").filename
        val stem = name.removeSuffix(".png")
        // yyyyMMdd_HHmmss → 15 chars, regardless of system TZ
        assertEquals(15, stem.length)
        assertTrue("missing underscore: $stem", stem.matches(Regex("\\d{8}_\\d{6}")))
    }

    @Test fun `custom created format with slashes still stays one segment`() {
        // format `yyyy/MM/dd` inside a variable should become one value because
        // TemplateContext scrubs '/' inside variable output.
        val t = Template.compile("out/{created:yyyy/MM/dd}.{ext}")
        val path = t.render(META, "png")
        assertEquals(listOf("out", "2024_08_15.png"), path.segments)
    }

    @Test fun `numeric fields render`() {
        val t = Template.compile("{w}x{h}-{id}.{ext}")
        val withSize = META.copy(width = 1920, height = 1080)
        assertEquals("1920x1080-1.png", t.render(withSize, "png").joinTo())
    }

    @Test fun `missing optional numeric renders empty`() {
        val t = Template.compile("{w}x{h}-{id}.{ext}")
        assertEquals("x-1.png", t.render(META, "png").joinTo())
    }
}
