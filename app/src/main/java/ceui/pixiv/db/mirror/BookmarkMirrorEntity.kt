package ceui.pixiv.db.mirror

import androidx.room.Entity
import androidx.room.Index

/**
 * 收藏镜像的一行 = 某个书架（[BookmarkShelf]）里的一件作品。
 *
 * ## 为什么要有这张表
 *
 * pixiv 的 `/v1/user/bookmarks/…` 只能**从新到旧**顺着 `max_bookmark_id` 游标翻，
 * 既不能倒序、不能跳页，也不能按作者/热度/年份/长宽比筛。想看三年前收藏的那张图，
 * 只能一路滑几百页（友商 pixez #1323 就是这个诉求）。在服务端能力不变的前提下，
 * 唯一的根治办法是**把整个收藏列表镜像到本地**，之后所有排序与筛选都在 SQLite 里做。
 *
 * ## 收藏顺序怎么表达（[bookmarkSeq]）
 *
 * pixiv 不回收藏时间，只保证列表是「新收藏在前」。所以顺序由本地分配的单调序号承载，
 * **约定：值越大 = 收藏得越晚**。
 *
 * - **首次全量回填**从头往后走，第一条（最新的那条）拿 0，之后逐条递减：0, -1, -2 …
 *   于是 `ORDER BY bookmarkSeq DESC` = 官方顺序，`ASC` = 倒序（#1323 要的那个）。
 *   用「从 0 递减」而不是「从 N 递增」，是因为回填开始时根本不知道总数 N。
 * - **之后的增量维护**只从头走，新发现的条目拿正数 1, 2, 3 …（同一批里越靠前 = 越新 =
 *   数越大）。新条目天然大于所有存量条目，顺序自洽，**永远不需要给老行重新编号**。
 *
 * 这就是「同步完成过一次，以后只维护」的关键：全量只跑一次，之后每次维护只碰表头那几页。
 *
 * ## 为什么要去规范化这一堆列
 *
 * [payloadJson] 已经是完整的 `Illust` / `Novel` JSON，渲染卡片够用了。但「花式筛选」
 * 要的是**在 SQLite 里**按作者、热度、年份、AI、R-18、页数、长宽比过滤和排序 ——
 * 逐行反序列化 3 万条 JSON 再在内存里筛，是几百毫秒到几秒的量级，且必然 OOM 风险。
 * 所以把可筛选的标量在入库时一次性摊平成列，配上复合索引（见 [Entity.indices]），
 * 查询就是纯索引扫描；JSON 只在真正要渲染那 30 张卡时才解析。
 *
 * 索引一律以 `shelfKey` 打头：一张表存 N 个书架，任何查询的第一个条件都是它，
 * 索引前缀不是它就等于全表扫。带 `bookmarkSeq` 收尾的复合索引则让「低基数筛选 +
 * 默认排序」（例：只看漫画、按收藏倒序）完全走索引，不产生临时排序。
 */
@Entity(
    tableName = "bookmark_mirror_table",
    primaryKeys = ["shelfKey", "targetId"],
    indices = [
        // 默认排序（正序 = 官方顺序，倒序 = #1323）；也是分页 keyset 的走索引路径
        Index(value = ["shelfKey", "bookmarkSeq"]),
        // 其余排序键
        Index(value = ["shelfKey", "createDateMs"]),
        Index(value = ["shelfKey", "totalBookmarks"]),
        Index(value = ["shelfKey", "totalView"]),
        Index(value = ["shelfKey", "textLength"]),
        Index(value = ["shelfKey", "title"]),
        // 按宽高比排（最竖长 / 最横扁）：范围条件 aspectRatio > 0 与排序键同列，一趟索引扫完
        Index(value = ["shelfKey", "aspectRatio"]),
        // 高基数筛选：按作者看收藏（顺带带上排序键，作者页内的排序也不用临时表）
        Index(value = ["shelfKey", "authorId", "bookmarkSeq"]),
        Index(value = ["shelfKey", "seriesId"]),
        // 低基数筛选 + 默认排序的组合：单独索引没意义，配上 bookmarkSeq 才值钱
        Index(value = ["shelfKey", "workType", "bookmarkSeq"]),
        Index(value = ["shelfKey", "xRestrict", "bookmarkSeq"]),
        Index(value = ["shelfKey", "aiType", "bookmarkSeq"]),
        Index(value = ["shelfKey", "pageCount", "bookmarkSeq"]),
        Index(value = ["shelfKey", "orientation", "bookmarkSeq"]),
        // 全量重扫收尾时按代号删除失联行
        Index(value = ["shelfKey", "generation"]),
        // 取消收藏时不知道它在哪个书架（公开/悄悄），按作品 id 跨书架删
        Index(value = ["targetId"]),
    ],
)
data class BookmarkMirrorEntity(
    /** 分区键，见 [BookmarkShelf.key]。 */
    val shelfKey: String,
    /** 作品 id（illust id 或 novel id）。与 [shelfKey] 组成主键。 */
    val targetId: Long,

    // ── 书架身份的展开列（shelfKey 已经能推出它们，摊平只为免去在 SQL 里解析字符串）──
    val ownerUid: Long,
    val contentType: Int,
    /** 见 [MirrorRestrict.code]。列名避开 SQLite 的 RESTRICT 关键字。 */
    val restrictCode: Int,

    /** 收藏顺序序号，越大越新。见类文档。 */
    val bookmarkSeq: Long,

    /** 完整的 `ceui.pixiv.api.model.Illust` / `ceui.loxia.Novel` JSON，渲染时才反序列化。 */
    val payloadJson: String,

    // ── 去规范化的筛选/排序列 ──────────────────────────────────────────────
    val title: String,
    val authorId: Long,
    val authorName: String,
    /** `illust` / `manga` / `ugoira` / `novel`。 */
    val workType: String,
    /** 插画页数；小说恒 1。 */
    val pageCount: Int,
    val width: Int,
    val height: Int,
    /** 宽高比（width/height），未知为 0。小说恒 0。 */
    val aspectRatio: Float,
    /**
     * 画幅取向，见 `BookmarkMirrorMapper.ORIENTATION_*`：0=未知 1=横图 2=竖图 3=方图。
     * 由 [aspectRatio] 归一而来 —— 「只看横图当壁纸」这种筛选按分档走索引，
     * 比对浮点比值做范围扫描既快又符合直觉。
     */
    val orientation: Int,
    val totalBookmarks: Int,
    val totalView: Int,
    /** 小说字数；插画恒 0。 */
    val textLength: Int,
    /** 作品发布时间（epoch ms），解析不出为 0。 */
    val createDateMs: Long,
    /** pixiv 的 AI 标记：0=未知 1=否 2=是。 */
    val aiType: Int,
    /** 0=全年龄 1=R-18 2=R-18G。 */
    val xRestrict: Int,
    val sanityLevel: Int,
    /** 作品是否仍可见（被删/仅限好P友 = false）。 */
    val isVisible: Boolean,
    val isMuted: Boolean,
    val seriesId: Long,
    val tagCount: Int,

    /**
     * 全文检索列：标题 + 作者名 + 全部标签（含译名），**已小写归一**。
     *
     * 刻意不上 FTS4：pixiv 的标题/标签绝大多数是日文，而 Android 系统 SQLite 的
     * `simple` / `unicode61` 分词器都不切 CJK —— 建了 FTS 也只对拉丁词有效，
     * 却要额外背一张影子表 + 四个同步触发器 + 一套迁移。这里一行文本配 `LIKE '%kw%'`
     * 对 CJK 是**正确**的，在单个书架几万行的量级上也就几十毫秒。
     */
    val searchText: String,

    /** 这一行最近一次从网络刷新的时间。 */
    val syncedAt: Long,
    /**
     * 写入这一行时的全量扫描代号。全量重扫收尾时删掉 `generation < 当前代号` 的行，
     * 即「服务端已经没有它了」——这是唯一能发现「在别处取消了收藏」的机制。
     */
    val generation: Int,
)
