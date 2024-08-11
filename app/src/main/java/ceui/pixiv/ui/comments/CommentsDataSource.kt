package ceui.pixiv.ui.comments

import androidx.lifecycle.MutableLiveData
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.loxia.ProgressImageButton
import ceui.loxia.ProgressTextButton
import ceui.pixiv.ui.common.DataSource

class CommentsDataSource(
    private val args: CommentsFragmentArgs,
    private val childCommentsMap: HashMap<Long, List<Comment>> = hashMapOf()
) : DataSource<Comment, CommentResponse>(
    dataFetcher = { Client.appApi.getIllustComments(args.illustId) },
    itemMapper = { comment ->
        listOf(
            CommentHolder(
                comment,
                args.illustArthurId,
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

    suspend fun showMoreReply(commentId: Long) {
        val resp = Client.appApi.getIllustReplyComments(commentId)
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
                    ex.printStackTrace()
                }
            }
        }
    }

    suspend fun sendComment() {
        val content = editingComment.value ?: return
        if (content.isBlank() || content.isEmpty()) {
            return
        }

        val parentCommentId = replyToComment.value?.id ?: 0L
        if (parentCommentId > 0L) {
            val resp = Client.appApi.postComment(args.illustId, content, parentCommentId)
            resp.comment?.let {
                updateItem(parentCommentId) { old ->
                    val childComments = old.childComments + listOf(it)
                    childCommentsMap[parentCommentId] = childComments
                    CommentHolder(old.comment, args.illustArthurId, childComments = childComments)
                }
            }
        } else {
            val resp = Client.appApi.postComment(args.illustId, content)
            resp.comment?.let {
                val itemHolders = pickItemHolders()
                val existing = (itemHolders.value ?: listOf()).toMutableList()
                existing.add(0, CommentHolder(it, args.illustArthurId))
                itemHolders.value = existing
            }
        }
        replyToComment.value = null
        editingComment.value = ""
    }

    suspend fun deleteComment(commentId: Long, parentCommentId: Long) {
        Client.appApi.deleteComment(commentId)
        if (parentCommentId > 0L) {
            updateItem(parentCommentId) { old ->
                val childComments = old.childComments.toMutableList()
                childComments.removeIf { it.id == commentId }
                childCommentsMap[parentCommentId] = childComments
                CommentHolder(
                    old.comment.copy(has_replies = childComments.isNotEmpty()),
                    args.illustArthurId,
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