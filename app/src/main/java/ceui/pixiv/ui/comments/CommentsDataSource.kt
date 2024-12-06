package ceui.pixiv.ui.comments

import androidx.lifecycle.MutableLiveData
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.loxia.ObjectType
import ceui.pixiv.ui.common.DataSource
import timber.log.Timber

class CommentsDataSource(
    private val args: CommentsFragmentArgs,
    private val childCommentsMap: HashMap<Long, List<Comment>> = hashMapOf()
) : DataSource<Comment, CommentResponse>(
    dataFetcher = {
        if (args.objectType == ObjectType.ILLUST) {
            Client.appApi.getIllustComments(args.objectId)
        } else {
            Client.appApi.getNovelComments(args.objectId)
        }
    },
    itemMapper = { comment ->
        listOf(
            CommentHolder(
                comment,
                args.objectArthurId,
                childCommentsMap[comment.id] ?: listOf()
            )
        )
    },
    filter = { comment ->
        comment.comment?.contains("翻墙") != true && comment.comment?.contains("VPN") != true
    }
) {

    val editingComment = MutableLiveData<String>()
    val replyToComment = MutableLiveData<Comment?>()
    val replyParentComment = MutableLiveData<Long?>()

    suspend fun showMoreReply(commentId: Long) {
        val resp = Client.appApi.getIllustReplyComments(args.objectType, commentId)
        childCommentsMap[commentId] = resp.comments
        updateItem(commentId) { old ->
            CommentHolder(
                old.comment,
                old.illustArthurId,
                resp.comments,
            )
        }
    }

    private fun updateItem(id: Long, update: (CommentHolder) -> CommentHolder) {
        val itemHolders = pickItemHolders()
        itemHolders.value?.let { currentHolders ->
            val index = currentHolders.indexOfFirst { it.getItemId() == id }
            if (index != -1) {
                try {
                    val target = currentHolders[index] as CommentHolder
                    val updated = update(target)
                    val updatedHolders = currentHolders.toMutableList().apply {
                        set(index, updated)
                    }
                    itemHolders.value = updatedHolders
                } catch (ex: Exception) {
                    Timber.e(ex)
                }
            }
        }
    }

    suspend fun sendComment() {
        val content = editingComment.value ?: return
        if (content.isBlank() || content.isEmpty()) {
            return
        }

        val parentCommentId = if ((replyParentComment.value ?: 0L) > 0L) {
            replyParentComment.value ?: 0L
        } else {
            replyToComment.value?.id ?: 0L
        }
        if (parentCommentId > 0L) {
            val resp = if (args.objectType == ObjectType.ILLUST) {
                Client.appApi.postIllustComment(args.objectId, content, parentCommentId)
            } else {
                Client.appApi.postNovelComment(args.objectId, content, parentCommentId)
            }
            resp.comment?.let {
                updateItem(parentCommentId) { old ->
                    val childComments = listOf(it) + old.childComments
                    childCommentsMap[parentCommentId] = childComments
                    CommentHolder(old.comment, args.objectArthurId, childComments = childComments)
                }
            }
        } else {
            val resp = if (args.objectType == ObjectType.ILLUST) {
                Client.appApi.postIllustComment(args.objectId, content)
            } else {
                Client.appApi.postNovelComment(args.objectId, content)
            }
            resp.comment?.let {
                val itemHolders = pickItemHolders()
                val existing = (itemHolders.value ?: listOf()).toMutableList()
                existing.add(0, CommentHolder(it, args.objectArthurId))
                itemHolders.value = existing
            }
        }
        replyToComment.value = null
        replyParentComment.value = null
        editingComment.value = ""
    }

    suspend fun deleteComment(commentId: Long, parentCommentId: Long) {
        Client.appApi.deleteComment(args.objectType, commentId)
        if (parentCommentId > 0L) {
            updateItem(parentCommentId) { old ->
                val childComments = old.childComments.toMutableList()
                childComments.removeIf { it.id == commentId }
                childCommentsMap[parentCommentId] = childComments
                CommentHolder(
                    old.comment.copy(has_replies = childComments.isNotEmpty()),
                    args.objectArthurId,
                    childComments = childComments
                )
            }
        } else {
            val itemHolders = pickItemHolders()
            val existing = (itemHolders.value ?: listOf()).toMutableList()
            existing.removeIf { it.getItemId() == commentId }
            itemHolders.value = existing
        }
    }
}