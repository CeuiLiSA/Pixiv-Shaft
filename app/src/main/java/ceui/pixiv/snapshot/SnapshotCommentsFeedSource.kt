package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.ui.comments.CommentFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 快照评论页的本地数据源：直接读快照 comments.json，复用现有 CommentFeedItem /
 * CommentCardRenderer / ChildCommentAdapter 渲染链路。
 */
class SnapshotCommentsFeedSource(
    private val snapshotId: String,
    private val illustArthurId: Long,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        if (cursor != null) return FeedPage(emptyList(), null)
        val data = withContext(Dispatchers.IO) {
            SnapshotRepository.loadViewerData(Shaft.getContext(), snapshotId)
        }
        val items = data.comments?.threads?.map { thread ->
            CommentFeedItem(
                comment = data.localizeComment(thread.comment),
                illustArthurId = illustArthurId,
                childComments = thread.replies.map { reply -> data.localizeComment(reply) },
                repliesLoaded = true,
            )
        }.orEmpty()
        return FeedPage(items, null)
    }
}