package ceui.pixiv.ui.comments

import androidx.lifecycle.MutableLiveData
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.loxia.ProgressTextButton
import ceui.loxia.RefreshState
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
    }
) {

    suspend fun showMoreReply(commentId: Long, sender: ProgressTextButton) {
        try {
            sender.showProgress()
            val resp = Client.appApi.getIllustReplyComments(commentId)
            childCommentsMap[commentId] = resp.comments
            updateItem<CommentHolder>(commentId) { old ->
                CommentHolder(
                    old.comment,
                    args.illustArthurId,
                    resp.comments,
                )
            }
        } finally {
            sender.hideProgress()
        }
    }
}