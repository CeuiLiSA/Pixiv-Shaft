package ceui.pixiv.ui.comments

import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.loxia.ProgressTextButton
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListItemHolder

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
        comment.comment?.contains("翻墙") != true
    }
) {

    suspend fun showMoreReply(commentId: Long, sender: ProgressTextButton) {
        try {
            sender.showProgress()
            val resp = Client.appApi.getIllustReplyComments(commentId)
            childCommentsMap[commentId] = resp.comments
            updateItem(commentId) { old ->
                CommentHolder(
                    old.comment,
                    old.illustArthurId,
                    resp.comments,
                )
            }
        } finally {
            sender.hideProgress()
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
}