package ceui.pixiv.db.mirror

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import java.util.Locale

/**
 * 「花式筛选」的筛选条件。**纯数据、可 Parcelize 之外的一切都不掺**，界面把它当状态传，
 * [BookmarkMirrorQuery] 把它翻译成 SQL。
 *
 * 加一个筛选维度只需要：这里加一个字段 → [BookmarkMirrorQuery.appendWhere] 加一段条件 →
 * （若要走索引）在 [BookmarkMirrorEntity] 加列与索引。三处对齐，别处不动。
 */
data class BookmarkFilter(
    val shelfKey: String,

    /** 自由文本，空格分词后逐词 AND 匹配 [BookmarkMirrorEntity.searchText]。 */
    val keyword: String = "",

    /** 必须命中的标签（**小写归一**后的 tagName）。 */
    val tagNames: List<String> = emptyList(),
    /** true = 全部命中（AND），false = 命中任一（OR）。 */
    val tagMatchAll: Boolean = true,
    /** 必须不命中的标签（小写归一）。 */
    val excludedTagNames: List<String> = emptyList(),

    val authorIds: List<Long> = emptyList(),
    /** `illust` / `manga` / `ugoira` / `novel`，空 = 不限。 */
    val workTypes: List<String> = emptyList(),
    /** 见 `BookmarkMirrorMapper.ORIENTATION_*`，空 = 不限。 */
    val orientations: List<Int> = emptyList(),

    val ai: AiFilter = AiFilter.ANY,
    val age: AgeFilter = AgeFilter.ANY,
    val pages: PageFilter = PageFilter.ANY,
    val validity: ValidityFilter = ANY_VALIDITY,

    val minBookmarks: Int? = null,
    val maxBookmarks: Int? = null,
    val minTextLength: Int? = null,
    val maxTextLength: Int? = null,
    /** 作品发布时间区间（epoch ms），闭区间；null = 不限。 */
    val createdFromMs: Long? = null,
    val createdToMs: Long? = null,
    /** 只看属于某个系列的作品。 */
    val seriesOnly: Boolean = false,

    val sort: BookmarkSort = BookmarkSort.BOOKMARK_NEWEST,
    /** [BookmarkSort.RANDOM] 的种子：同一个种子翻页顺序稳定，换一个就重新洗牌。 */
    val randomSeed: Long = 1L,
) {
    /** 除了书架本身，用户还额外加了条件吗（界面上「清空筛选」按钮的可用性）。 */
    val hasAnyCondition: Boolean
        get() = keyword.isNotBlank() || tagNames.isNotEmpty() || excludedTagNames.isNotEmpty() ||
            authorIds.isNotEmpty() || workTypes.isNotEmpty() || orientations.isNotEmpty() ||
            ai != AiFilter.ANY || age != AgeFilter.ANY || pages != PageFilter.ANY ||
            validity != ANY_VALIDITY || minBookmarks != null || maxBookmarks != null ||
            minTextLength != null || maxTextLength != null ||
            createdFromMs != null || createdToMs != null || seriesOnly

    companion object {
        val ANY_VALIDITY = ValidityFilter.ANY
    }
}

enum class AiFilter { ANY, ONLY_AI, EXCLUDE_AI }

enum class AgeFilter { ANY, ALL_AGES, R18, R18G }

enum class PageFilter { ANY, SINGLE_PAGE, MULTI_PAGE }

enum class ValidityFilter { ANY, VALID_ONLY, INVALID_ONLY }

/**
 * 排序方式。[orderBy] 里的列名是**白名单常量**，永远不拼用户输入 —— 用户给的值一律走
 * `?` 绑定。
 *
 * [BOOKMARK_OLDEST] 就是这套系统存在的直接理由（友商 pixez #1323：收藏多了以后想看
 * 很久以前收藏的东西，只能一路滑）。服务端给不了倒序，本地表一句 `ASC` 就有了。
 */
enum class BookmarkSort(
    val orderBy: String,
    /**
     * 这个排序键在**单个书架内是否已经唯一**。唯一就不需要再补全序兜底键 —— 而少补一列，
     * `(shelfKey, <排序键>)` 索引就能独力满足整个 ORDER BY，SQLite 不再 `USE TEMP B-TREE`
     * （真机 1000 行实测：默认排序和倒序都从「索引扫描 + 临时排序」降成纯索引扫描）。
     * [BOOKMARK_NEWEST] / [BOOKMARK_OLDEST] 走的 `bookmarkSeq` 按构造就是书架内唯一的
     * （见 BookmarkMirrorEntity 的号段设计），其余排序键（发布时间、人气、页数、字数、标题）
     * 大量并列，必须补兜底键，否则 LIMIT/OFFSET 翻页会漏条目 / 出重复。
     */
    val uniqueKey: Boolean = false,
    /**
     * 排序键是宽高比时顺带要求 `aspectRatio > 0`。放进 WHERE 而不是 ORDER BY 表达式：
     * 范围条件和排序键是同一列，`(shelfKey, aspectRatio)` 索引一趟就把过滤和排序都做了。
     */
    val requiresKnownRatio: Boolean = false,
) {
    /** 收藏时间：新 → 旧（= pixiv 官方顺序）。 */
    BOOKMARK_NEWEST("bookmarkSeq DESC", uniqueKey = true),
    /** 收藏时间：旧 → 新。#1323。 */
    BOOKMARK_OLDEST("bookmarkSeq ASC", uniqueKey = true),
    /**
     * 作品发布时间：新 → 旧 / 旧 → 新。
     *
     * 刻意**不**写成 `createDateMs = 0 ASC, createDateMs DESC` 去把「发布时间未知」的
     * 作品钉在末尾：ORDER BY 里一出现表达式，`(shelfKey, createDateMs)` 索引就用不上了，
     * 三万行每次都要建临时排序表。而 `createDateMs = 0` 只发生在被删/不可见、payload 几乎
     * 全空的作品上（极少），代价是它们在「旧 → 新」里排最前面 —— 用这点小别扭换全程走索引。
     */
    CREATED_NEWEST("createDateMs DESC"),
    CREATED_OLDEST("createDateMs ASC"),
    /** 人气（总收藏数）。 */
    POPULAR_DESC("totalBookmarks DESC"),
    POPULAR_ASC("totalBookmarks ASC"),
    /** 浏览量。 */
    VIEWS_DESC("totalView DESC"),
    /** 页数（找「长漫画」用）。 */
    PAGES_DESC("pageCount DESC"),
    /**
     * 宽高比（width/height）：最竖长 → 最横扁 / 最横扁 → 最竖长。
     * 「画幅」那一档只分横/竖/方三档，找**最**细长的手机壁纸、**最**宽的桌面壁纸还是得按比值排。
     * 比值未知（0，被删/不可见作品）的行会被 [requiresKnownRatio] 从结果里挡掉，
     * 否则「最竖长」会先吐出一串失效作品。
     */
    RATIO_TALLEST("aspectRatio ASC", requiresKnownRatio = true),
    RATIO_WIDEST("aspectRatio DESC", requiresKnownRatio = true),
    /** 字数（小说书架用）。 */
    LENGTH_DESC("textLength DESC"),
    LENGTH_ASC("textLength ASC"),
    /**
     * 标题字典序，找特定作品用。不加 `COLLATE NOCASE`：pixiv 标题以日文为主，
     * 大小写折叠几乎没有意义，却会让 `(shelfKey, title)` 索引失效。
     */
    TITLE_ASC("title ASC"),
    /** 随机漫游：种子在 SQL 里参与哈希，翻页稳定。 */
    RANDOM(""),
    ;

    val isRandom: Boolean get() = this == RANDOM
}

/**
 * [BookmarkFilter] → 可执行的 SQL。
 *
 * 全部走 [SimpleSQLiteQuery] + 位置参数：条件片段是代码里的常量字符串，用户输入
 * （关键词、标签名、作者 id、数值区间）一律 `?` 绑定，没有任何一处字符串拼接用户值。
 *
 * 四个出口共用同一段 WHERE：
 * - [rows]        列表本体（LIMIT/OFFSET 分页）
 * - [count]       命中总数（界面上的「共 N 件」）
 * - [tagFacets]   当前结果里的标签云（= 可以继续下钻的标签及其条数）
 * - [authorFacets] 当前结果里的作者云
 */
object BookmarkMirrorQuery {

    private const val ROWS = "bookmark_mirror_table"
    private const val TAGS = "bookmark_mirror_tag_table"

    /** LIKE 的转义符。标题/标签里真出现 `%` `_` 的时候不该当通配符。 */
    private const val LIKE_ESCAPE = '\\'

    fun rows(filter: BookmarkFilter, limit: Int, offset: Int): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = buildWhere(filter, args)
        val sql = buildString {
            append("SELECT * FROM ").append(ROWS)
            append(" WHERE ").append(where)
            append(" ORDER BY ").append(orderClause(filter))
            append(" LIMIT ? OFFSET ?")
        }
        args += limit
        args += offset
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    fun count(filter: BookmarkFilter): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = buildWhere(filter, args)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM $ROWS WHERE $where", args.toTypedArray())
    }

    /**
     * 当前筛选结果里的标签频次表。
     *
     * 刻意把已选标签也算进筛选条件（而不是把它们从条件里摘掉）：这样列出来的就是
     * 「和已选标签**共现**的标签」，正是继续下钻时想看的东西。
     */
    fun tagFacets(filter: BookmarkFilter, limit: Int): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        args += filter.shelfKey
        val where = buildWhere(filter, args)
        val sql = "SELECT t.tagName AS tagName, MAX(t.displayName) AS displayName, " +
            "MAX(t.translatedName) AS translatedName, COUNT(*) AS hitCount " +
            "FROM $TAGS t WHERE t.shelfKey = ? AND t.targetId IN " +
            "(SELECT targetId FROM $ROWS WHERE $where) " +
            "GROUP BY t.tagName ORDER BY hitCount DESC, t.tagName ASC LIMIT ?"
        args += limit
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    fun authorFacets(filter: BookmarkFilter, limit: Int): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = buildWhere(filter, args)
        val sql = "SELECT authorId AS authorId, MAX(authorName) AS authorName, COUNT(*) AS hitCount " +
            // 排除空作者名：失效/被删作品的 payload 里 user.name 是空串，放进作者云会渲染成
            // 一个光秃秃的数字 chip，点了也只是那几件失效作品（真机上 71 行）。
            // 它们由「作品状态」那一档筛选覆盖，不该混进作者维度。
            "FROM $ROWS WHERE $where AND authorId > 0 AND authorName != '' " +
            "GROUP BY authorId ORDER BY hitCount DESC, authorName ASC LIMIT ?"
        args += limit
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    private fun orderClause(filter: BookmarkFilter): String {
        val primary = if (filter.sort.isRandom) {
            // 确定性洗牌：种子由界面生成、是本地 Long，不是用户输入，可以安全内联
            // （绑定参数放进 ORDER BY 表达式在部分 SQLite 版本上会被当成常量列序号）。
            "(targetId * 2654435761 + ${filter.randomSeed}) % 1000000007"
        } else {
            filter.sort.orderBy
        }
        // 排序键本身已经唯一时不再补兜底键：多那一列会让 SQLite 为「ORDER BY 的最后一项」
        // 起一棵临时 B 树，而它对唯一键毫无意义。键不唯一时兜底键是必须的：并列行的相对
        // 次序 SQLite 不保证，而我们是 LIMIT/OFFSET 翻页的，次序一抖就会出现某条重复出现、
        // 另一条永远看不到。
        return if (filter.sort.uniqueKey) primary else "$primary, targetId DESC"
    }

    /** 拼 WHERE，同时把绑定值按出现顺序追加进 [args]。 */
    private fun buildWhere(filter: BookmarkFilter, args: MutableList<Any?>): String {
        val clauses = mutableListOf<String>()

        clauses += "shelfKey = ?"
        args += filter.shelfKey

        appendKeyword(filter, clauses, args)
        appendTags(filter, clauses, args)
        appendInList(clauses, args, "authorId", filter.authorIds)
        appendInList(clauses, args, "workType", filter.workTypes)
        appendInList(clauses, args, "orientation", filter.orientations)

        when (filter.ai) {
            AiFilter.ANY -> Unit
            // pixiv 的 ai_type：0=未知 1=否 2=是。只有 2 是明确的 AI 生成。
            AiFilter.ONLY_AI -> clauses += "aiType = 2"
            AiFilter.EXCLUDE_AI -> clauses += "aiType != 2"
        }

        when (filter.age) {
            AgeFilter.ANY -> Unit
            AgeFilter.ALL_AGES -> clauses += "xRestrict = 0"
            AgeFilter.R18 -> clauses += "xRestrict = 1"
            AgeFilter.R18G -> clauses += "xRestrict = 2"
        }

        when (filter.pages) {
            PageFilter.ANY -> Unit
            PageFilter.SINGLE_PAGE -> clauses += "pageCount <= 1"
            PageFilter.MULTI_PAGE -> clauses += "pageCount > 1"
        }

        when (filter.validity) {
            ValidityFilter.ANY -> Unit
            ValidityFilter.VALID_ONLY -> clauses += "isVisible = 1"
            // 「失效的收藏」单独看得见，才谈得上清理它们
            ValidityFilter.INVALID_ONLY -> clauses += "isVisible = 0"
        }

        appendRange(clauses, args, "totalBookmarks", filter.minBookmarks, filter.maxBookmarks)
        appendRange(clauses, args, "textLength", filter.minTextLength, filter.maxTextLength)
        // 发布时间未知（0）的作品不该混进任何一个年份区间里
        if (filter.createdFromMs != null || filter.createdToMs != null) {
            clauses += "createDateMs > 0"
        }
        appendRange(clauses, args, "createDateMs", filter.createdFromMs, filter.createdToMs)

        if (filter.seriesOnly) clauses += "seriesId > 0"
        if (filter.sort.requiresKnownRatio) clauses += "aspectRatio > 0"

        return clauses.joinToString(" AND ")
    }

    /**
     * 关键词：按空白分词，**逐词 AND**。
     *
     * 分词而不是整串匹配，是因为用户脑子里的检索是「东方 + 灵梦」而不是一个连续子串；
     * 而逐词 AND 而不是 OR，是因为多打一个词的意图永远是「再缩小一点」。
     */
    private fun appendKeyword(filter: BookmarkFilter, clauses: MutableList<String>, args: MutableList<Any?>) {
        val terms = filter.keyword.trim().lowercase(Locale.ROOT)
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_KEYWORD_TERMS)
        terms.forEach { term ->
            val asId = term.toLongOrNull()?.takeIf { it > 0L }
            if (asId != null) {
                // 纯数字：既可能是想找某一件作品（illust_id），也可能是想找某个画师的全部收藏
                //（作者 uid）—— 用户脑子里就是「我把这串数字贴进来」，不该逼他先选类型。
                // 同时保留文本匹配：标题/标签里本来就带数字的作品照样命中。
                clauses += "(searchText LIKE ? ESCAPE '$LIKE_ESCAPE' OR targetId = ? OR authorId = ?)"
                args += "%${escapeLike(term)}%"
                args += asId
                args += asId
            } else {
                clauses += "searchText LIKE ? ESCAPE '$LIKE_ESCAPE'"
                args += "%${escapeLike(term)}%"
            }
        }
    }

    private fun appendTags(filter: BookmarkFilter, clauses: MutableList<String>, args: MutableList<Any?>) {
        val include = filter.tagNames.filter { it.isNotBlank() }.distinct()
        if (include.isNotEmpty()) {
            val placeholders = include.joinToString(",") { "?" }
            if (filter.tagMatchAll) {
                clauses += "targetId IN (SELECT targetId FROM $TAGS WHERE shelfKey = ? AND tagName IN ($placeholders) " +
                    "GROUP BY targetId HAVING COUNT(DISTINCT tagName) = ?)"
                args += filter.shelfKey
                args.addAll(include)
                args += include.size
            } else {
                clauses += "targetId IN (SELECT targetId FROM $TAGS WHERE shelfKey = ? AND tagName IN ($placeholders))"
                args += filter.shelfKey
                args.addAll(include)
            }
        }
        val exclude = filter.excludedTagNames.filter { it.isNotBlank() }.distinct()
        if (exclude.isNotEmpty()) {
            val placeholders = exclude.joinToString(",") { "?" }
            clauses += "targetId NOT IN (SELECT targetId FROM $TAGS WHERE shelfKey = ? AND tagName IN ($placeholders))"
            args += filter.shelfKey
            args.addAll(exclude)
        }
    }

    private fun appendInList(clauses: MutableList<String>, args: MutableList<Any?>, column: String, values: List<Any>) {
        if (values.isEmpty()) return
        val distinct = values.distinct()
        clauses += "$column IN (${distinct.joinToString(",") { "?" }})"
        args.addAll(distinct)
    }

    private fun appendRange(
        clauses: MutableList<String>,
        args: MutableList<Any?>,
        column: String,
        min: Number?,
        max: Number?,
    ) {
        if (min != null) {
            clauses += "$column >= ?"
            args += min
        }
        if (max != null) {
            clauses += "$column <= ?"
            args += max
        }
    }

    private fun escapeLike(raw: String): String = buildString(raw.length + 4) {
        raw.forEach { ch ->
            if (ch == '%' || ch == '_' || ch == LIKE_ESCAPE) append(LIKE_ESCAPE)
            append(ch)
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /** 关键词分词的上限：多到这个数已经不是检索而是误输入，再多只是白白拖慢 LIKE。 */
    private const val MAX_KEYWORD_TERMS = 8
}
