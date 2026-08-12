package ceui.pixiv.ui.novel.reader.model

/**
 * 小说正文「自动混排插画」的取材来源（issue #999）：
 * - [None]     不混排（默认），正文保持纯文字；
 * - [Followed] 从当前账号关注的画师新作（/v2/illust/follow）取图；
 * - [Discover] 从本地发现页候选池（[ceui.pixiv.db.discovery.DiscoveryPool]）取图。
 */
enum class NovelIllustSource {
    None,
    Followed,
    Discover,
}
