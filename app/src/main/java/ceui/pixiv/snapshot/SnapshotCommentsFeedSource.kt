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

    // 同 SnapshotArtworkFeedSource：整段留在 IO 上，逐条评论的本地化不回主线程做。
    override suspend fun load(cursor: String?): FeedPage<String> = withContext(Dispatchers.IO) {
        if (cursor != null) return@withContext FeedPage(emptyList(), null)
        val data = SnapshotRuntimeCache.get(snapshotId)
            ?: SnapshotRepository.loadViewerData(Shaft.getContext(), snapshotId)
                .also { SnapshotRuntimeCache.put(snapshotId, it) }
        val items = data.comments?.threads?.map { thread ->
            CommentFeedItem(
                comment = data.localizeComment(thread.comment),
                illustArthurId = illustArthurId,
                childComments = thread.replies.map { reply -> data.localizeComment(reply) },
                repliesLoaded = true,
            )
        }.orEmpty()
        FeedPage(items, null)
    }
}