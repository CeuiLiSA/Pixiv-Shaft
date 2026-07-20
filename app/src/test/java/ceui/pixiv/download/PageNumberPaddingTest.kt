package ceui.pixiv.download

import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.template.PageNumbering
import ceui.pixiv.download.template.Template
import ceui.pixiv.download.template.TemplateValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** #721 — zero-padded `{page}` so multi-page works sort correctly in galleries. */
class PageNumberPaddingTest {

    private fun meta(page: Int, totalPages: Int) = ItemMeta(
        id = 42L,
        title = "Work",
        author = Author(id = 7L, name = "Alice"),
        createdAt = Instant.parse("2024-01-02T03:04:05Z"),
        page = page,
        totalPages = totalPages,
    )

    private val T = Template.compile("{title}_{id}_p{page}.{ext}")

    private val OFF = PageNumbering(indexFrom1 = true, padded = false)
    private val ON = PageNumbering(indexFrom1 = true, padded = true)

    @Test fun `padding off leaves page numbers bare`() {
        assertEquals("Work_42_p2.jpg", T.render(meta(1, 4), "jpg", OFF).joinTo())
        assertEquals("Work_42_p10.jpg", T.render(meta(9, 12), "jpg", OFF).joinTo())
    }

    @Test fun `padding widens to the work's highest page number`() {
        // 4 pages → 1 digit, but floored at 2 so the switch is visible.
        assertEquals("Work_42_p02.jpg", T.render(meta(1, 4), "jpg", ON).joinTo())
        assertEquals("Work_42_p10.jpg", T.render(meta(9, 12), "jpg", ON).joinTo())
        // 150 pages → 3 digits.
        assertEquals("Work_42_p007.jpg", T.render(meta(6, 150), "jpg", ON).joinTo())
        assertEquals("Work_42_p150.jpg", T.render(meta(149, 150), "jpg", ON).joinTo())
    }

    /** The actual point of the issue: text order must match page order. */
    @Test fun `padded names sort in page order as plain strings`() {
        val names = (0 until 12).map { T.render(meta(it, 12), "jpg", ON).joinTo() }
        assertEquals(names, names.sorted())

        val unpadded = (0 until 12).map { T.render(meta(it, 12), "jpg", OFF).joinTo() }
        assertTrue("unpadded names are expected to sort wrongly", unpadded != unpadded.sorted())
    }

    @Test fun `zero index still pads consistently`() {
        val numbering = PageNumbering(indexFrom1 = false, padded = true)
        assertEquals("Work_42_p00.jpg", T.render(meta(0, 12), "jpg", numbering).joinTo())
        // 0-based tops out at 11 → still 2 digits, unlike the 1-based case.
        assertEquals("Work_42_p11.jpg", T.render(meta(11, 12), "jpg", numbering).joinTo())
    }

    @Test fun `explicit mask overrides the switch in both directions`() {
        val t = Template.compile("{title}_p{page:0000}.{ext}")
        assertEquals("Work_p0002.jpg", t.render(meta(1, 4), "jpg", OFF).joinTo())
        assertEquals("Work_p0002.jpg", t.render(meta(1, 4), "jpg", ON).joinTo())
    }

    @Test fun `page1 alias honours padding too`() {
        val t = Template.compile("{title}_p{page1}.{ext}")
        assertEquals("Work_p02.jpg", t.render(meta(1, 4), "jpg", ON).joinTo())
    }

    /**
     * A malformed mask must not throw: SafeTemplateRender would swallow it and
     * silently swap in the bucket default, replacing the user's whole scheme.
     */
    @Test fun `a malformed mask renders leniently rather than throwing`() {
        val t = Template.compile("{title}_p{page:xx}.{ext}")
        assertEquals("Work_p02.jpg", t.render(meta(1, 4), "jpg", ON).joinTo())
        assertEquals("Work_p2.jpg", t.render(meta(1, 4), "jpg", OFF).joinTo())
    }

    /** …but it is reported at save time, so the typo is not invisible. */
    @Test fun `a malformed mask is reported by the validator`() {
        val bad = TemplateValidator.validate("{title}_p{page:xx}.{ext}", Bucket.Illust)
        assertFalse(bad.ok)
        assertTrue(bad.errors.any { "page:xx" in it.message })

        assertTrue(TemplateValidator.validate("{title}_p{page:000}.{ext}", Bucket.Illust).ok)
        // Absurd widths are a typo too — 7 zeros is past MAX_PAGE_WIDTH.
        assertFalse(TemplateValidator.validate("{title}_p{page:0000000}.{ext}", Bucket.Illust).ok)
    }

    /** Degenerate metadata must not produce a negative pad width / crash. */
    @Test fun `bogus totalPages does not crash`() {
        listOf(0, 1, -3).forEach { total ->
            val zeroBased = PageNumbering(indexFrom1 = false, padded = true)
            assertEquals("Work_42_p00.jpg", T.render(meta(0, total), "jpg", zeroBased).joinTo())
            assertEquals("Work_42_p01.jpg", T.render(meta(0, total), "jpg", ON).joinTo())
        }
    }

    /** A page beyond the declared width keeps all its digits — never truncated. */
    @Test fun `mask never truncates an over-wide page number`() {
        val t = Template.compile("{title}_p{page:00}.{ext}")
        assertEquals("Work_p123.jpg", t.render(meta(122, 200), "jpg", ON).joinTo())
    }
}
