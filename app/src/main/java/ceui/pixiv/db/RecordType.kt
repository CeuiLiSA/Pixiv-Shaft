package ceui.pixiv.db

object RecordType {
    const val VIEW_ILLUST_HISTORY = 1
    const val VIEW_NOVEL_HISTORY = 2
    const val VIEW_USER_HISTORY = 3

    const val BLOCK_ILLUST = 4
    const val BLOCK_NOVEL = 5
    const val BLOCK_USER = 6

    // 稍后再看:本地收藏一批想稍后浏览的插画。仅本地(不上报云端),复用 general_table
    // 存 ceui.loxia.Illust JSON,渲染走 IllustCardHolder,与浏览历史同一套。
    const val WATCH_LATER = 7

    // 稍后再看(小说)。**必须是独立的 recordType,不能跟 WATCH_LATER 共用再靠 entityType 分流**:
    // general_table 的主键是 (id, recordType),不含 entityType;而插画 id 与小说 id 是两条互相
    // 独立的自增序列(小说 id 现在约 2600 万,2012 年前的老插画 id 全落在这个区间),同号完全可能。
    // 共用一个 recordType 时,加入同号小说会被 OnConflictStrategy.REPLACE 静默覆盖掉那张插画,
    // 而按 (recordType, id) 删除又会把两条一起删。分开就没有这些事。
    const val WATCH_LATER_NOVEL = 8

    // 置顶作者:本地把常看的画师钉在搜索首页。存 ceui.loxia.User 全量 JSON(要头像和昵称),
    // 与「置顶标签」刻意分开 —— 后者是 search_table.pinned 的搜索历史行,两者挤在同一排
    // 格子里正是用户在反馈的问题;而且作者按 uid 定位,改名/重名都不会跑偏。
    const val PINNED_USER = 9
}