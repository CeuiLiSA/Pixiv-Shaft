package ceui.pixiv.ui.comments

import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListItemHolder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class CommentsDataSource(private val args: CommentsFragmentArgs) :
    DataSource<Comment, CommentResponse>(
        loader = { Client.appApi.getIllustComments(args.illustId) },
        mapper = { comment -> listOf(CommentHolder(comment, args.illustArthurId)) }
    )
{

    suspend fun showMoreReply(commentId: Long) {
        val resp = Client.appApi.getIllustReplyComments(commentId)
        update<CommentHolder>(commentId) { old ->
            CommentHolder(old.comment, args.illustArthurId, resp.comments)
        }
    }
}