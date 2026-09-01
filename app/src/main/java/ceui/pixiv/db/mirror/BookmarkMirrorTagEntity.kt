package ceui.pixiv.db.mirror

import androidx.room.Entity
import androidx.room.Index

/**
 * 收藏镜像的标签倒排表：一件作品的每个标签一行。
 *
 * 主表 [BookmarkMirrorEntity] 已经把标签拼进 `searchText` 了，为什么还要单开一张？
 * 因为「花式筛选」对标签的要求不是模糊匹配，而是**集合运算**：
 *
 * - 精确命中：`#オリジナル` 不该匹配到 `#オリジナル衣装`；
 * - 多标签 AND：同时带 A 和 B 的作品（`GROUP BY targetId HAVING COUNT(*) = 2`）；
 * - 标签云 / facet 计数：这个书架里每个标签各有多少件，按频次排序 —— 这是
 *   「我到底收藏过些什么」最直观的入口，用 `LIKE` 是算不出来的。
 *
 * 这三件事在倒排表上都是走索引的一次聚合，在主表上则是全表 + 逐行拆字符串。
 *
 * [tagName] 是**小写归一**后的名字（匹配与去重都按它），[displayName] 保留原始大小写
 * 用于展示，[translatedName] 给中文用户看译名。主键 (shelfKey, targetId, tagName)
 * 天然把同一作品的重名标签去重（pixiv 偶尔会重复下发）。
 */
@Entity(
    tableName = "bookmark_mirror_tag_table",
    primaryKeys = ["shelfKey", "targetId", "tagName"],
    indices = [
        // 按标签筛选 / 标签云聚合：书架内按标签名走索引
        Index(value = ["shelfKey", "tagName"]),
        // 主表某行更新或删除时，连带清掉它的标签行
        Index(value = ["shelfKey", "targetId"]),
        // 跨书架按作品 id 清理（取消收藏时不知道它在哪个书架）
        Index(value = ["targetId"]),
    ],
)
data class BookmarkMirrorTagEntity(
    val shelfKey: String,
    val targetId: Long,
    /** 小写归一后的标签名，匹配与聚合都按它。 */
    val tagName: String,
    /** 原始大小写的标签名，用于展示。 */
    val displayName: String,
    /** pixiv 给的译名，没有则空串。 */
    val translatedName: String,
)

/** 标签云 / facet 查询的投影：一个标签 + 它在当前筛选结果里的命中数。 */
data class BookmarkTagFacet(
    val tagName: String,
    val displayName: String,
    val translatedName: String,
    val hitCount: Int,
)

/** 作者 facet 的投影：一个作者 + 他在当前书架里被收藏了多少件。 */
data class BookmarkAuthorFacet(
    val authorId: Long,
    val authorName: String,
    val hitCount: Int,
)
