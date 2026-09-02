package ceui.pixiv.db.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookmarkMirrorQuery] 的形状与安全性。
 *
 * 这里断言的是 **SQL 文本与绑定值的对应关系**，不跑真库（那属于 androidTest）。
 * 值得单测的理由有两条，两条都不是「覆盖率」：
 *  1. 拼 SQL 的地方一旦把用户输入拼进字符串就是注入，这条必须被机器盯着；
 *  2. `bookmarkSeq ASC` 是整个功能存在的理由（pixez #1323 的倒序），
 *     谁不小心把它改成 DESC，编译器不会说话。
 */
class BookmarkMirrorQueryTest {

    private val shelf = "0:0:12345"

    @Test
    fun `倒序排的是 bookmarkSeq 升序`() {
        val sql = BookmarkMirrorQuery
            .rows(BookmarkFilter(shelfKey = shelf, sort = BookmarkSort.BOOKMARK_OLDEST), 30, 0)
            .sql
        assertTrue(sql, sql.contains("ORDER BY bookmarkSeq ASC"))
    }

    @Test
    fun `默认排序是官方顺序`() {
        val sql = BookmarkMirrorQuery.rows(BookmarkFilter(shelfKey = shelf), 30, 0).sql
        assertTrue(sql, sql.contains("ORDER BY bookmarkSeq DESC"))
    }

    /**
     * 排序键不唯一 → 必须补全序兜底键（否则 LIMIT/OFFSET 翻页会漏条目 / 出重复）；
     * 已唯一 → 必须**不**补（多那一列会让 SQLite 起临时 B 树，真机深翻页实测 1.12ms → 0.14ms）。
     */
    @Test
    fun `兜底键只在排序键不唯一时才补`() {
        BookmarkSort.entries.forEach { sort ->
            val sql = BookmarkMirrorQuery
                .rows(BookmarkFilter(shelfKey = shelf, sort = sort), 30, 0).sql
            val hasTieBreaker = sql.contains(", targetId DESC LIMIT")
            assertEquals("$sort: $sql", !sort.uniqueKey, hasTieBreaker)
        }
    }

    /** 只有 bookmarkSeq 系的排序可以声明唯一 —— 它按号段设计在书架内天然唯一。 */
    @Test
    fun `声明为唯一键的排序只能是收藏顺序`() {
        assertEquals(
            setOf(BookmarkSort.BOOKMARK_NEWEST, BookmarkSort.BOOKMARK_OLDEST),
            BookmarkSort.entries.filter { it.uniqueKey }.toSet(),
        )
    }

    @Test
    fun `纯数字关键词同时按作品 id 和作者 uid 命中`() {
        val query = BookmarkMirrorQuery
            .rows(BookmarkFilter(shelfKey = shelf, keyword = "38297201"), 30, 0)
        assertTrue(query.sql, query.sql.contains("targetId = ? OR authorId = ?"))
        val args = query.captureArgs()
        assertTrue(args.toString(), args.contains("%38297201%"))
        assertEquals(2, args.count { it == 38297201L })
    }

    @Test
    fun `非数字关键词不掺 id 条件`() {
        val sql = BookmarkMirrorQuery
            .rows(BookmarkFilter(shelfKey = shelf, keyword = "miku"), 30, 0).sql
        assertFalse(sql, sql.contains("targetId = ?"))
    }

    /** 比值未知（0）= 被删/不可见作品，不挡掉的话「最竖长」会先吐出一串失效作品。 */
    @Test
    fun `按宽高比排序时挡掉比值未知的行`() {
        listOf(BookmarkSort.RATIO_TALLEST to "aspectRatio ASC", BookmarkSort.RATIO_WIDEST to "aspectRatio DESC")
            .forEach { (sort, orderBy) ->
                val sql = BookmarkMirrorQuery.rows(BookmarkFilter(shelfKey = shelf, sort = sort), 30, 0).sql
                assertTrue(sql, sql.contains("aspectRatio > 0"))
                assertTrue(sql, sql.contains("ORDER BY $orderBy, targetId DESC"))
                // count 走同一段 WHERE，「共 N 件」和列表必须对得上
                assertTrue(BookmarkMirrorQuery.count(BookmarkFilter(shelfKey = shelf, sort = sort)).sql.contains("aspectRatio > 0"))
            }
        val plain = BookmarkMirrorQuery.rows(BookmarkFilter(shelfKey = shelf), 30, 0).sql
        assertFalse(plain, plain.contains("aspectRatio"))
    }

    @Test
    fun `作者云排掉没有作者名的失效作品`() {
        val sql = BookmarkMirrorQuery.authorFacets(BookmarkFilter(shelfKey = shelf), 50).sql
        assertTrue(sql, sql.contains("authorName != ''"))
    }

    @Test
    fun `关键词按空白分词逐词 AND，且只以绑定值出现`() {
        val query = BookmarkMirrorQuery.rows(
            BookmarkFilter(shelfKey = shelf, keyword = "东方 灵梦"), 30, 0
        )
        assertEquals(2, Regex("searchText LIKE \\?").findAll(query.sql).count())
        assertFalse(query.sql, query.sql.contains("东方"))
        val args = query.captureArgs()
        assertTrue(args.toString(), args.contains("%东方%"))
        assertTrue(args.toString(), args.contains("%灵梦%"))
    }

    @Test
    fun `关键词里的 LIKE 通配符被转义`() {
        val args = BookmarkMirrorQuery
            .rows(BookmarkFilter(shelfKey = shelf, keyword = "50%_off"), 30, 0)
            .captureArgs()
        assertTrue(args.toString(), args.contains("%50\\%\\_off%"))
    }

    @Test
    fun `单引号不会拼进 SQL —— 注入面在这里闭合`() {
        val evil = "'; DROP TABLE bookmark_mirror_table; --"
        val query = BookmarkMirrorQuery.rows(
            BookmarkFilter(shelfKey = shelf, keyword = evil, tagNames = listOf(evil)), 30, 0
        )
        assertFalse(query.sql, query.sql.contains("DROP"))
        assertFalse(query.sql, query.sql.contains("'") && query.sql.contains("--"))
    }

    @Test
    fun `多标签 AND 用 HAVING 计数，OR 不用`() {
        val and = BookmarkMirrorQuery.rows(
            BookmarkFilter(shelfKey = shelf, tagNames = listOf("a", "b"), tagMatchAll = true), 30, 0
        )
        assertTrue(and.sql, and.sql.contains("HAVING COUNT(DISTINCT tagName) = ?"))
        assertTrue(and.captureArgs().toString(), and.captureArgs().contains(2L))

        val or = BookmarkMirrorQuery.rows(
            BookmarkFilter(shelfKey = shelf, tagNames = listOf("a", "b"), tagMatchAll = false), 30, 0
        )
        assertFalse(or.sql, or.sql.contains("HAVING"))
    }

    @Test
    fun `排除标签用 NOT IN`() {
        val sql = BookmarkMirrorQuery
            .rows(BookmarkFilter(shelfKey = shelf, excludedTagNames = listOf("r-18")), 30, 0).sql
        assertTrue(sql, sql.contains("targetId NOT IN"))
    }

    @Test
    fun `发布时间区间会顺带排掉时间未知的行`() {
        val sql = BookmarkMirrorQuery.rows(
            BookmarkFilter(shelfKey = shelf, createdFromMs = 1_000L, createdToMs = 2_000L), 30, 0
        ).sql
        // 否则 createDateMs=0（解析失败的失效作品）会混进每一个年份区间
        assertTrue(sql, sql.contains("createDateMs > 0"))
    }

    @Test
    fun `绑定值顺序与占位符顺序一致`() {
        val query = BookmarkMirrorQuery.rows(
            BookmarkFilter(
                shelfKey = shelf,
                keyword = "kw",
                tagNames = listOf("t1", "t2"),
                excludedTagNames = listOf("t3"),
                authorIds = listOf(7L),
                minBookmarks = 500,
            ),
            30, 5,
        )
        assertEquals(
            query.sql.count { it == '?' },
            query.captureArgs().size,
        )
        // shelfKey 永远是第一个绑定值（每条查询的第一个 WHERE 条件）
        assertEquals(shelf, query.captureArgs().first())
        // LIMIT/OFFSET 永远收尾
        assertEquals(listOf<Any?>(30L, 5L), query.captureArgs().takeLast(2))
    }

    @Test
    fun `hasAnyCondition 只认收窄条件，不认排序`() {
        assertFalse(BookmarkFilter(shelfKey = shelf, sort = BookmarkSort.RANDOM).hasAnyCondition)
        assertTrue(BookmarkFilter(shelfKey = shelf, seriesOnly = true).hasAnyCondition)
        assertTrue(BookmarkFilter(shelfKey = shelf, keyword = "x").hasAnyCondition)
    }
}
