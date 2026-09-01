package ceui.pixiv.ui.pivision

import ceui.pixiv.api.model.Article
import ceui.pixiv.feeds.FeedItem

/**
 * pixivision 特辑条目：只持不可变的 [Article]（data class）。
 *
 * 特辑没有收藏/关注这类可变状态，点开就是网页——不像插画侧要并存 legacy 可变 bean，
 * 内容相等性直接靠 data class 深比较驱动 DiffUtil。
 */
class ArticleFeedItem(val article: Article) : FeedItem {

    override val feedKey: Any get() = article.id

    override fun equals(other: Any?): Boolean {
        return other is ArticleFeedItem && other.article == article
    }

    override fun hashCode(): Int = article.hashCode()
}
