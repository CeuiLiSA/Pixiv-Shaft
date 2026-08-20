package ceui.pixiv.ui.search.v3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 锁定 [SearchTarget.toQueryValue] 的语义（#906）：
 * 默认档「标签部分一致」必须不传 search_target（返回 null）——显式传 partial_match_for_tags
 * 会让 pixiv 做严格 tag 匹配并忽略 merge_plain_keyword_results，关键字只出现在标题里的作品
 * （如「淫神空间」系列小说）就搜不到。其余档位是用户显式选择，必须原样透传。
 *
 * 注意：null 只是「toQueryValue 层」的语义。小说端点不传时同义词不展开（#1038），
 * SearchNovelRepo 在默认档会把 null 转回显式 partial 并带空结果降级，见那边注释。
 */
class SearchTargetQueryValueTest {

    @Test
    fun partialMatchForTags_isOmitted() {
        // 默认档 → null（retrofit 不发这个 query 参数），让标题命中也能搜到
        assertNull(SearchTarget.toQueryValue(SearchTarget.PartialMatchForTags.apiValue))
    }

    @Test
    fun explicitTargets_passThrough() {
        // 用户显式选择的档位原样透传——严格匹配语义不能丢
        assertEquals("exact_match_for_tags",
            SearchTarget.toQueryValue(SearchTarget.ExactMatchForTags.apiValue))
        assertEquals("title_and_caption",
            SearchTarget.toQueryValue(SearchTarget.TitleAndCaption.apiValue))
        assertEquals("text",
            SearchTarget.toQueryValue(SearchTarget.NovelText.apiValue))
        assertEquals("keyword",
            SearchTarget.toQueryValue(SearchTarget.NovelKeyword.apiValue))
    }

    @Test
    fun nullStaysNull() {
        // legacy SearchModel 里 searchType 可能还没 seed（null）——保持 null 即不传，fail-safe
        assertNull(SearchTarget.toQueryValue(null))
    }
}
