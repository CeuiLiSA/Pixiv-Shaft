package ceui.pixiv.download

import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.TemplateValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateValidatorTest {

    @Test fun `empty template is error`() {
        val r = TemplateValidator.validate("")
        assertFalse(r.ok)
    }

    @Test fun `unterminated brace is error`() {
        val r = TemplateValidator.validate("Shaft/{title.{ext}")
        assertFalse(r.ok)
    }

    @Test fun `trailing separator is error`() {
        val r = TemplateValidator.validate("Shaft/{title}/")
        assertFalse(r.ok)
    }

    @Test fun `illust template without id emits warning`() {
        val r = TemplateValidator.validate("Shaft/{title}.{ext}", Bucket.Illust)
        assertTrue(r.ok)
        assertTrue(r.warnings.any { it.message.contains("{id}") })
    }

    @Test fun `illust template with ext variable passes without warning about extension`() {
        val r = TemplateValidator.validate("Shaft/{id}.{ext}", Bucket.Illust)
        assertTrue(r.ok)
        assertFalse(r.warnings.any { it.message.contains("{ext}") })
    }

    @Test fun `literal extension also satisfies ext rule`() {
        val r = TemplateValidator.validate("Shaft/{id}.png", Bucket.Illust)
        assertTrue(r.ok)
        assertFalse(r.warnings.any { it.message.contains("{ext}") })
    }

    @Test fun `novel bucket does not need ext`() {
        val r = TemplateValidator.validate("Shaft/Novels/{id}.txt", Bucket.Novel)
        assertTrue(r.ok)
        assertTrue(r.warnings.isEmpty())
    }
}
