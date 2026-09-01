package ceui.pixiv.ui.comments

import ceui.lisa.activities.Shaft
import ceui.lisa.helper.CommentFilter
import ceui.pixiv.api.model.Comment
import ceui.pixiv.api.model.CommentResponse
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase

/**
 * 一条主评论 + 已展开的回复线程。[feedKey] 用评论 id：翻页追加/本地编辑（发送、删除、
 * 展开更多回复）全部通过 [ceui.pixiv.feeds.FeedViewModel] 的 mutateItems/updateItems/removeItems
 * 原语操作这份不可变数据，items 是否为空天然驱动空态展示，不需要额外的空态旗标。
 */
data class CommentFeedItem(
    val comment: Comment,
    val illustArthurId: Long,
    val childComments: List<Comment> = emptyList(),
    /** Whether the complete reply thread has been fetched from the server. */
    val repliesLoaded: Boolean = false,
) : FeedItem {

    override val feedKey: Any get() = comment.id

    val isArthurCommented: Boolean
        get() = illustArthurId == comment.user.id
}

/** 翻页只产出新的主评论，从不携带已展开的回复（那是本地交互态，见 [CommentFeedItem]）。 */
fun mapCommentsPage(response: CommentResponse, illustArthurId: Long, phase: FeedLoadPhase): List<FeedItem> {
    return response.comments
        .filterNot { isSpamComment(it) }
        .map { CommentFeedItem(it, illustArthurId) }
}

internal fun isSpamComment(comment: Comment): Boolean {
    if (!Shaft.sSettings.isFilterComment()) return false
    return CommentFilter.judgeText(comment.comment)
}

internal fun filterSpamComments(comments: List<Comment>): List<Comment> {
    if (!Shaft.sSettings.isFilterComment()) return comments
    return comments.filterNot { CommentFilter.judgeText(it.comment) }
}
