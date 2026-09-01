package ceui.pixiv.ui.pinned

import ceui.loxia.User
import ceui.pixiv.feeds.FeedItem

/**
 * 「我置顶的内容 → 作者」列表里的一条。
 *
 * [User] 是从 general_table 存的 JSON 反序列化出来的快照（头像 / 昵称可能过期，点进去
 * 拿的是新鲜数据）；每次重查 DB 都是全新实例，靠 [feedKey] 用 uid 做身份去重即可。
 */
class PinnedUserItemHolder(val user: User) : FeedItem {

    override val feedKey: Any get() = user.id
}
