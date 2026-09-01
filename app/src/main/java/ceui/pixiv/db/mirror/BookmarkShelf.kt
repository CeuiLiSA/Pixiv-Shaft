package ceui.pixiv.db.mirror

import ceui.lisa.utils.Params

/**
 * 一个可镜像的「收藏书架」= 谁的收藏 × 什么内容 × 什么可见性。
 *
 * 整套镜像系统（表、引擎、查询）都以 [BookmarkShelf] 为分区单位，**不是**围绕
 * 「我的插画公开收藏」这一种情况写死的：插画/小说、公开/悄悄收藏（private）各自
 * 是一个独立书架，各自有独立的续传游标与完成标记，互不干扰；将来要镜像别人的
 * 收藏（画师主页的收藏 tab）也只是多一个 ownerUid，不需要动引擎。
 *
 * [key] 是入库的分区列（`bookmark_mirror_table.shelfKey`），所有查询的第一个
 * WHERE 条件、所有复合索引的第一列都是它——一张表存 N 个书架，但每次查询只在
 * 自己那一段里走索引。
 *
 * 编码刻意是「type:restrict:uid」而不是 uid 打头：书架数量极少（每账号 4 个），
 * 前缀可读性远比前缀分布重要，日志里一眼能认出是哪个书架。
 */
data class BookmarkShelf(
    val ownerUid: Long,
    val contentType: MirrorContentType,
    val restrict: MirrorRestrict,
) {
    val key: String = "${contentType.code}:${restrict.code}:$ownerUid"

    /** 日志用的人类可读标签，例：`illust/public#12345`。 */
    val label: String get() = "${contentType.tag}/${restrict.apiValue}#$ownerUid"

    companion object {
        /** 从 [key] 还原。格式不认识返回 null（库里读到脏行时用，不抛）。 */
        fun parse(key: String): BookmarkShelf? {
            val parts = key.split(':')
            if (parts.size != 3) return null
            val type = parts[0].toIntOrNull()?.let(MirrorContentType::of) ?: return null
            val restrict = parts[1].toIntOrNull()?.let(MirrorRestrict::of) ?: return null
            val uid = parts[2].toLongOrNull() ?: return null
            return BookmarkShelf(uid, type, restrict)
        }

        /** 当前登录用户的四个书架（插画/小说 × 公开/悄悄收藏）。 */
        fun allOf(ownerUid: Long): List<BookmarkShelf> =
            MirrorContentType.entries.flatMap { type ->
                MirrorRestrict.entries.map { restrict -> BookmarkShelf(ownerUid, type, restrict) }
            }
    }
}

/**
 * 镜像的内容类型。[code] 是入库值，**不能改**（改了等于把存量行的类型全认错）。
 */
enum class MirrorContentType(val code: Int, val tag: String) {
    ILLUST(0, "illust"),
    NOVEL(1, "novel"),
    ;

    companion object {
        fun of(code: Int): MirrorContentType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 收藏可见性。[apiValue] 直接是 pixiv 的 `restrict` 参数值，[code] 是入库值（不能改）。
 *
 * PRIVATE 就是「悄悄收藏」：它与 PUBLIC 是**两条互不相交的列表**（pixiv 的
 * `/v1/user/bookmarks/…` 一次只回一种），所以必须是两个书架，不能靠一列布尔混在一起
 * ——混在一起就没法各自记续传游标，也没法各自判「同步完成过一次」。
 */
enum class MirrorRestrict(val code: Int, val apiValue: String) {
    PUBLIC(0, Params.TYPE_PUBLIC),
    PRIVATE(1, Params.TYPE_PRIVATE),
    ;

    companion object {
        fun of(code: Int): MirrorRestrict? = entries.firstOrNull { it.code == code }

        /** 从 pixiv 的 restrict 字符串还原；不认识按 PUBLIC 兜底（对齐各调用点的默认值）。 */
        fun ofApiValue(value: String?): MirrorRestrict =
            if (value == Params.TYPE_PRIVATE) PRIVATE else PUBLIC
    }
}
