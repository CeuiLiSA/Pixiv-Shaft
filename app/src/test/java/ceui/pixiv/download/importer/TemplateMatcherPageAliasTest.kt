package ceui.pixiv.download.importer

import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.template.PageNumbering
import ceui.pixiv.download.template.TemplateContext
import ceui.pixiv.download.template.TemplateParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TemplateMatcherPageAliasTest {

    private fun roundTrip(
        template: String,
        configuredBase: PageBase,
        page: Int = 3,
        totalPages: Int = 12,
    ): NameMatch {
        val context = TemplateContext(
            ItemMeta(
                id = 123456789L,
                title = "sample",
                author = Author(42L, "author"),
                createdAt = Instant.EPOCH,
                page = page,
                totalPages = totalPages,
            ),
            ext = "jpg",
            numbering = PageNumbering(indexFrom1 = configuredBase != PageBase.ZERO),
        )
        val filename = buildString {
            TemplateParser(template).parseAll().forEach { it.render(context, this) }
        }
        val matcher = requireNotNull(TemplateMatcher.compile(template, configuredBase))
        return requireNotNull(matcher.match(filename))
    }

    @Test fun `page1 stays one-based when global numbering starts at zero`() {
        val hit = roundTrip("{id}_p{page1}.{ext}", PageBase.ZERO)
        assertEquals(123456789L, hit.illustId)
        assertEquals(4, hit.printedPage)
        assertEquals(PageBase.ONE, hit.pageBase)
    }

    @Test fun `page1 declares its base even when the configured base is unknown`() {
        val hit = roundTrip("{id}_p{page1}.{ext}", PageBase.UNKNOWN)
        assertEquals(4, hit.printedPage)
        assertEquals(PageBase.ONE, hit.pageBase)
    }

    @Test fun `padded page1 inside a conditional remains one-based`() {
        val hit = roundTrip("{id}[?p>1:_p{page1:000}].{ext}", PageBase.ZERO)
        assertEquals(4, hit.printedPage)
        assertEquals(PageBase.ONE, hit.pageBase)
    }

    @Test fun `ordinary page retains the configured base`() {
        for (base in PageBase.entries) {
            val hit = roundTrip("{id}_p{page}.{ext}", base)
            assertEquals(if (base == PageBase.ZERO) 3 else 4, hit.printedPage)
            assertEquals(base, hit.pageBase)
        }
    }

    @Test fun `base follows the captured page variable when aliases are mixed`() {
        val ordinaryLast = roundTrip("{id}_{page1}_{page}.{ext}", PageBase.ZERO)
        assertEquals(3, ordinaryLast.printedPage)
        assertEquals(PageBase.ZERO, ordinaryLast.pageBase)

        val aliasLast = roundTrip("{id}_{page}_{page1}.{ext}", PageBase.ZERO)
        assertEquals(4, aliasLast.printedPage)
        assertEquals(PageBase.ONE, aliasLast.pageBase)
    }

    @Test fun `omitted conditional alias does not invent a page number`() {
        val hit = roundTrip("{id}[?p>1:_p{page1}].{ext}", PageBase.ZERO, page = 0, totalPages = 1)
        assertEquals(null, hit.printedPage)
    }
}
