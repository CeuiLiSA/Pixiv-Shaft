package ceui.lisa.helper

import ceui.lisa.models.NovelBean
import ceui.lisa.models.TagsBean
import ceui.loxia.Novel
import ceui.loxia.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小说列表自动屏蔽（issue #743）：正文字数区间 + 超长标签名。
 *
 * 打的是 [IllustNovelFilter.judgeNovelSpam] 阈值显式传入的那层——读 Shaft.sSettings 的便捷重载
 * 会触发 Application 子类的类初始化，在裸 JVM 单测里必炸，所以阈值在生产代码里就分了层。
 *
 * 每条断言都同时过 legacy [NovelBean]（走 Mapper，搜索等列表）和 loxia [Novel]（走 NovelFeedItem，
 * feeds 框架列表）两个重载并要求判定一致——不一致的话用户在搜索页和首页推荐会看到两套结果。
 */
class NovelSpamFilterTest {

    private companion object {
        const val OFF = 0
    }

    private fun bean(textLength: Int, vararg tagNames: String): NovelBean =
        NovelBean().apply {
            text_length = textLength
            tags = tagNames.map { TagsBean().apply { name = it } }
        }

    private fun novel(textLength: Int, vararg tagNames: String): Novel =
        Novel(id = 1L, text_length = textLength, tags = tagNames.map { Tag(name = it) })

    /** 两个重载判定一致 + 命中预期，这是本 feature 的核心不变式。 */
    private fun assertSpam(
        expected: Boolean,
        textLength: Int,
        vararg tagNames: String,
        minLength: Int = OFF,
        maxLength: Int = OFF,
        maxTagNameLength: Int = OFF,
    ) {
        val fromBean = IllustNovelFilter.judgeNovelSpam(
            bean(textLength, *tagNames), minLength, maxLength, maxTagNameLength
        )
        val fromNovel = IllustNovelFilter.judgeNovelSpam(
            novel(textLength, *tagNames), minLength, maxLength, maxTagNameLength
        )
        assertEquals(
            "NovelBean / Novel 两个重载判定不一致 (len=$textLength, tags=${tagNames.toList()})",
            fromBean,
            fromNovel,
        )
        if (expected) assertTrue(fromBean) else assertFalse(fromBean)
    }

    @Test
    fun `三个阈值默认全 0 时什么都不屏蔽`() {
        assertSpam(false, 12, "加V信abcdefg包月看全本更新超快速来")
        assertSpam(false, 999999)
    }

    @Test
    fun `正文字数低于下限被屏蔽,等于下限放行`() {
        assertSpam(true, 80, minLength = 500)
        assertSpam(true, 499, minLength = 500)
        assertSpam(false, 500, minLength = 500)
        assertSpam(false, 5000, minLength = 500)
    }

    @Test
    fun `正文字数高于上限被屏蔽,等于上限放行`() {
        assertSpam(false, 80000, maxLength = 80000)
        assertSpam(true, 80001, maxLength = 80000)
    }

    @Test
    fun `上下限同时开时只留区间内的`() {
        assertSpam(true, 100, minLength = 500, maxLength = 20000)
        assertSpam(false, 5000, minLength = 500, maxLength = 20000)
        assertSpam(true, 30000, minLength = 500, maxLength = 20000)
    }

    /** 拿不到字数（0 / 缺字段）一律放行——宁可漏杀也不能误杀正常作品。 */
    @Test
    fun `字数为 0 视为未知,不参与字数屏蔽`() {
        assertSpam(false, 0, minLength = 500)
        assertSpam(false, 0, maxLength = 100)
    }

    @Test
    fun `任一标签名超过长度上限就屏蔽`() {
        // 17 字的招揽话术塞进 tag 名 → 屏蔽
        assertSpam(true, 5000, "R-18", "加V信abcdefg包月看全本超快", maxTagNameLength = 16)
        // 正常日文长 tag（12 字）在 16 阈值下必须活着——这正是阈值取 16 而不是 12 的原因
        assertSpam(false, 5000, "R-18", "ぼくたちは勉強ができない", maxTagNameLength = 16)
    }

    @Test
    fun `标签名长度正好等于上限时放行`() {
        assertSpam(false, 5000, "a".repeat(16), maxTagNameLength = 16)
        assertSpam(true, 5000, "a".repeat(17), maxTagNameLength = 16)
    }

    @Test
    fun `没有标签时标签维度不误伤`() {
        assertSpam(false, 5000, maxTagNameLength = 16)
    }

    @Test
    fun `字数放行但标签超长仍然屏蔽`() {
        assertSpam(true, 5000, "a".repeat(20), minLength = 500, maxTagNameLength = 16)
    }
}
