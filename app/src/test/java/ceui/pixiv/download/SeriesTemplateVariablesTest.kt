package ceui.pixiv.download

import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.template.PageNumbering
import ceui.pixiv.download.template.Template
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** #964 — `{series}` / `{series_order}` 变量与 `[?series:…]` 条件块。 */
class SeriesTemplateVariablesTest {

    private val OFF = PageNumbering(indexFrom1 = true, padded = false)
    private val ON = PageNumbering(indexFrom1 = true, padded = true)

    private fun meta(order: Int? = null, total: Int? = null, inSeries: Boolean = true) = ItemMeta(
        id = 42L,
        title = "Chapter",
        author = Author(id = 7L, name = "Alice"),
        createdAt = Instant.parse("2024-01-02T03:04:05Z"),
        flags = if (inSeries) setOf(Flag.Series) else emptySet(),
        seriesTitle = if (inSeries) "My Series" else null,
        seriesOrder = order,
        seriesTotal = total,
    )

    @Test fun `series title and order render into the path`() {
        val t = Template.compile("{author}/{series}/{series_order} {title} {id}.txt")
        assertEquals(
            "Alice/My Series/3 Chapter 42.txt",
            t.render(meta(order = 3, total = 12), "txt", OFF).joinTo(),
        )
    }

    @Test fun `absent series collapses to empty and skips conditional block`() {
        val t = Template.compile("{author}/[?series:{series}/{series_order} ]{title} {id}.txt")
        assertEquals(
            "Alice/Chapter 42.txt",
            t.render(meta(inSeries = false), "txt", OFF).joinTo(),
        )
    }

    @Test fun `order without series total or padding stays bare`() {
        val t = Template.compile("{series_order}.txt")
        assertEquals("3.txt", t.render(meta(order = 3), "txt", OFF).joinTo())
    }

    @Test fun `unknown order renders empty`() {
        val t = Template.compile("x{series_order}y.txt")
        assertEquals("xy.txt", t.render(meta(order = null), "txt", OFF).joinTo())
    }

    @Test fun `global padding switch pads order to series total width`() {
        val t = Template.compile("{series_order}.txt")
        assertEquals("03.txt", t.render(meta(order = 3, total = 12), "txt", ON).joinTo())
        assertEquals("007.txt", t.render(meta(order = 7, total = 150), "txt", ON).joinTo())
    }

    @Test fun `explicit mask pins the width regardless of the switch`() {
        val t = Template.compile("{series_order:000}.txt")
        assertEquals("003.txt", t.render(meta(order = 3, total = 12), "txt", OFF).joinTo())
    }
}
