package ceui.pixiv.download

import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.DefaultTemplates
import ceui.pixiv.download.template.TemplateSamples
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TemplateSamplesTest {

    @Test fun `preview all default templates succeeds`() {
        Bucket.entries.forEach { b ->
            val src = DefaultTemplates.SOURCES.getValue(b)
            when (val result = TemplateSamples.preview(src, b)) {
                is TemplateSamples.Preview.Ok -> {
                    assertTrue("cleaned path empty for $b", result.cleaned.segments.isNotEmpty())
                }
                is TemplateSamples.Preview.Failure -> fail("bucket $b preview failed: ${result.message}")
            }
        }
    }

    @Test fun `preview failure surfaces parse error`() {
        val result = TemplateSamples.preview("bad/{unterminated", Bucket.Illust)
        assertTrue(result is TemplateSamples.Preview.Failure)
    }
}
