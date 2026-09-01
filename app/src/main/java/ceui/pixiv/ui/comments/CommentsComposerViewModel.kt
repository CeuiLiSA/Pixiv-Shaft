package ceui.pixiv.ui.comments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.pixiv.api.API
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Comment
import ceui.pixiv.api.model.ObjectType
import ceui.pixiv.feeds.FeedItem
import kotlinx.coroutines.sync.Mutex

/** Navigation-independent destination for comment network operations. */
data class CommentTarget(
    val objectId: Long,
    val objectType: String,
)

data class SentComment(
    val parentCommentId: Long,
    val comment: Comment,
)

internal class CommentProtocolException :
    IllegalStateException("Comment response did not contain a comment")

/**
 * 评论输入框状态 + 网络动作 + 「网络结果如何编辑列表」的纯函数。列表本身仍归
 * [ceui.pixiv.feeds.FeedViewModel] 持有（两个 VM 不互相持有引用，不产生循环依赖）——
 * Fragment 只做一件事：把 apply* 的返回值转手扔给 `feedViewModel.mutateItems { ... }`，
 * 「顶层插入 / 挂进回复线程 / 过滤删除」这些分支判断全部收口在本 VM，不散落进 Fragment。
 */
class CommentsComposerViewModel(
    private val target: CommentTarget,
    private val api: API = Client.appApi,
) : ViewModel() {

    private val sendMutex = Mutex()

    private val _editingComment = MutableLiveData("")
    val editingComment: LiveData<String> = _editingComment
    private var draftRevision = 0L

    private val _replyToComment = MutableLiveData<Comment?>(null)
    val replyToComment: LiveData<Comment?> = _replyToComment
    private var replyParentComment: Long? = null
    private var replyRevision = 0L

    fun updateDraft(content: String) {
        if (_editingComment.value != content) {
            draftRevision++
            _editingComment.value = content
        }
    }

    fun startReply(comment: Comment, parentCommentId: Long) {
        replyRevision++
        replyParentComment = parentCommentId
        _replyToComment.value = comment
    }

    fun cancelReply() {
        replyRevision++
        replyParentComment = null
        _replyToComment.value = null
    }

    suspend fun showMoreReply(commentId: Long): List<Comment> {
        val resp = api.getIllustReplyComments(target.objectType, commentId)
        return filterSpamComments(resp.comments)
    }

    /** 当前回复目标:优先取显式记录的 parentCommentId,退化到正在回复的评论自身 id,
     * 都没有则 0(顶层新评论)。sendComment/sendStamp 共用同一条解析规则。 */
    private fun snapshotReply(): ReplySnapshot = ReplySnapshot(
        parentCommentId = replyParentComment?.takeIf { it > 0L } ?: (_replyToComment.value?.id ?: 0L),
        revision = replyRevision,
    )

    /** 成功发出返回 (parentCommentId, 新评论)；parentCommentId<=0 表示顶层新评论。文本为空/请求无返回体时 null。 */
    suspend fun sendComment(): SentComment? {
        if (!sendMutex.tryLock()) return null
        try {
            val content = _editingComment.value.orEmpty()
            if (content.isBlank()) return null
            val sentDraftRevision = draftRevision
            val reply = snapshotReply()
            val resp = if (reply.parentCommentId > 0L) {
                if (target.objectType == ObjectType.ILLUST) {
                    api.postIllustComment(target.objectId, content, reply.parentCommentId)
                } else {
                    api.postNovelComment(target.objectId, content, reply.parentCommentId)
                }
            } else {
                if (target.objectType == ObjectType.ILLUST) {
                    api.postIllustComment(target.objectId, content)
                } else {
                    api.postNovelComment(target.objectId, content)
                }
            }
            val comment = resp.comment ?: throw CommentProtocolException()
            if (draftRevision == sentDraftRevision) {
                draftRevision++
                _editingComment.value = ""
            }
            clearReplyIfUnchanged(reply.revision)
            return SentComment(reply.parentCommentId, comment)
        } finally {
            sendMutex.unlock()
        }
    }

    /** 「表情贴图」选中即单发:comment 恒为空、只带 stamp_id(对齐官方 App 抓包行为,
     * 与打字互斥),沿用当前回复目标(若有)。 */
    suspend fun sendStamp(stampId: Long): SentComment? {
        if (!sendMutex.tryLock()) return null
        try {
            val reply = snapshotReply()
            val parentCommentId = reply.parentCommentId.takeIf { it > 0L }
            val resp = if (target.objectType == ObjectType.ILLUST) {
                api.postIllustComment(target.objectId, "", parentCommentId, stampId)
            } else {
                api.postNovelComment(target.objectId, "", parentCommentId, stampId)
            }
            val comment = resp.comment ?: throw CommentProtocolException()
            clearReplyIfUnchanged(reply.revision)
            return SentComment(reply.parentCommentId, comment)
        } finally {
            sendMutex.unlock()
        }
    }

    suspend fun deleteComment(commentId: Long) {
        api.deleteComment(target.objectType, commentId)
    }

    private fun clearReplyIfUnchanged(sentRevision: Long) {
        if (replyRevision == sentRevision) {
            replyRevision++
            replyParentComment = null
            _replyToComment.value = null
        }
    }

    private data class ReplySnapshot(
        val parentCommentId: Long,
        val revision: Long,
    )

    /** 发评论成功后如何编辑列表：parentCommentId<=0 顶层插入，否则挂进对应主评论的回复线程。 */
    fun applySentComment(
        items: List<FeedItem>,
        parentCommentId: Long,
        comment: Comment,
        illustArthurId: Long,
    ): List<FeedItem> {
        return if (parentCommentId > 0L) {
            items.map { item ->
                if (item is CommentFeedItem && item.comment.id == parentCommentId) {
                    // Do not mark the thread loaded here. The parent may already have replies that
                    // are still collapsed; this is only the one reply we just posted locally.
                    item.copy(childComments = listOf(comment) + item.childComments)
                } else item
            }
        } else {
            listOf<FeedItem>(CommentFeedItem(comment, illustArthurId)) + items
        }
    }

    /** 删评论成功后如何编辑列表：顶层评论直接摘除，子评论从所属回复线程里过滤掉。 */
    fun applyDeletedComment(items: List<FeedItem>, commentId: Long, parentCommentId: Long): List<FeedItem> {
        return if (parentCommentId > 0L) {
            items.map { item ->
                if (item is CommentFeedItem && item.comment.id == parentCommentId) {
                    val remaining = item.childComments.filterNot { it.id == commentId }
                    item.copy(
                        // Only a fully loaded thread can prove that no replies remain. For a
                        // collapsed thread, preserve the server's has_replies flag because its
                        // older replies are not represented in childComments yet.
                        comment = if (item.repliesLoaded) {
                            item.comment.copy(has_replies = remaining.isNotEmpty())
                        } else {
                            item.comment
                        },
                        childComments = remaining,
                    )
                } else item
            }
        } else {
            items.filterNot { it is CommentFeedItem && it.comment.id == commentId }
        }
    }

    /** 「查看回复」展开成功后，把拉到的子评论塞进对应主评论。 */
    fun applyExpandedReplies(items: List<FeedItem>, commentId: Long, children: List<Comment>): List<FeedItem> {
        return items.map { item ->
            if (item is CommentFeedItem && item.comment.id == commentId) {
                // A reply posted while this GET was in flight may not be present in its snapshot.
                // Preserve local replies and de-duplicate against the fetched thread.
                val merged = (item.childComments + children).distinctBy { it.id }
                item.copy(
                    comment = item.comment.copy(has_replies = merged.isNotEmpty()),
                    childComments = merged,
                    repliesLoaded = true,
                )
            } else item
        }
    }
}
