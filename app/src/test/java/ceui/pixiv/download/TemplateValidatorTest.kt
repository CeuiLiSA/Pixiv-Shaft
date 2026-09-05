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

    @Test fun `backup bucket accepts json extension`() {
        val r = TemplateValidator.validate("ShaftBackups/Shaft-Backup_{created:yyyyMMdd_HHmmss}.json", Bucket.Backup)
        assertTrue(r.ok)
        assertTrue(r.errors.isEmpty())
    }

    @Test fun `backup bucket rejects non json extension`() {
        val r = TemplateValidator.validate("ShaftBackups/Shaft-Backup_{created:yyyyMMdd_HHmmss}.zip", Bucket.Backup)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.message.contains(".json") })
    }

    @Test fun `backup bucket rejects ext variable`() {
        val r = TemplateValidator.validate("ShaftBackups/Shaft-Backup_{created:yyyyMMdd_HHmmss}.{ext}", Bucket.Backup)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.message.contains("{ext}") })
    }

    @Test fun `log bucket accepts txt extension`() {
        val r = TemplateValidator.validate("Shaft/Logs/{created:yyyyMMdd_HHmmss}.txt", Bucket.Log)
        assertTrue(r.ok)
        assertTrue(r.errors.isEmpty())
    }

    @Test fun `log bucket rejects non txt extension`() {
        val r = TemplateValidator.validate("Shaft/Logs/{created:yyyyMMdd_HHmmss}.log", Bucket.Log)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.message.contains(".txt") })
    }

    @Test fun `log bucket rejects ext variable`() {
        val r = TemplateValidator.validate("Shaft/Logs/{created:yyyyMMdd_HHmmss}.{ext}", Bucket.Log)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.message.contains("{ext}") })
    }

    /**
     * 复现 bug 的入口：`[?p<100:…]` 能编译（被当成名为 `p<100` 的 flag），
     * 旧校验只编译不渲染 → 放行保存 → 下载时崩。现在保存校验也渲染样本，
     * 必须在保存前就报错拦下。
     */
    @Test fun `unsupported condition operator is rejected before save`() {
        val r = TemplateValidator.validate(
            "ShaftImages/{title}_{id}[?p<100:_p{page}].{ext}", Bucket.Illust,
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.message.contains("p<100") })
    }

    /** 未知变量同理：编译过得了，渲染过不了，应在保存校验阶段就拦下。 */
    @Test fun `unknown variable is rejected before save`() {
        val r = TemplateValidator.validate("Shaft/{nope}.{ext}", Bucket.Illust)
        assertFalse(r.ok)
    }
}
