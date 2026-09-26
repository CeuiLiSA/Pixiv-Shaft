package ceui.pixiv.ui.pinned

import ceui.lisa.database.SearchDao
import ceui.lisa.database.SearchEntity
import ceui.lisa.utils.SearchTypeUtil

/**
 * 置顶的「标签组合」（对标 pixez#1364「收藏标签组合，而不只是单个标签」）。
 *
 * 组合不另建表：它就是 search_table 里一条 `pinned = 1` 的关键字搜索，keyword 是空格拼起来的
 * 多个词——和搜索页顶部 chip 行、搜索历史、「我置顶的内容」用的是同一份数据。单个标签是
 * 只有一个词的特例，所以置顶页、搜索首页的置顶区不需要为组合开第二条路。
 */

/** 与 SearchActivity 把入参 keyword 拆成 chip 的规则一致：按空白切、丢空串。 */
fun splitSearchTerms(keyword: String?): List<String> {
    if (keyword.isNullOrBlank()) return emptyList()
    return keyword.trim().split(WHITESPACE).filter { it.isNotEmpty() }
}

/**
 * 两组搜索词是不是「同一个组合」。
 *
 * pixiv 的空格是 AND，词序不影响结果：「原神 胡桃」和「胡桃 原神」是同一个组合，置顶了其中一个，
 * 另一个也该显示为已置顶，而不是再存一条。带 `OR` 的查询词序有语义，只认完全相同的顺序。
 */
fun sameSearchTerms(a: List<String>, b: List<String>): Boolean {
    if (a.size != b.size) return false
    if (OR_OPERATOR in a || OR_OPERATOR in b) return a == b
    return a.toSet() == b.toSet()
}

/** 「原神 + 胡桃」：置顶卡片标题、取消置顶确认与搜索页提示共用的组合显示名。 */
fun searchTermsDisplayName(terms: List<String>): String = terms.joinToString(" + ")

/**
 * 找到与 [terms] 等价的那条置顶关键字搜索；没有则 null。只认关键字类型——ID / URL 记录不是标签。
 * 读 Room，调用方负责放到 IO 线程。
 */
fun findPinnedSearch(dao: SearchDao, terms: List<String>): SearchEntity? {
    if (terms.isEmpty()) return null
    return dao.getAllPinned().firstOrNull {
        it.searchType == SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD &&
            sameSearchTerms(splitSearchTerms(it.keyword), terms)
    }
}

private val WHITESPACE = Regex("\\s+")
private const val OR_OPERATOR = "OR"
