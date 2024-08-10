package ceui.pixiv.ui.comments

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.ProgressTextButton
import ceui.loxia.User
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.setUpLinearLayout
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.refactor.ppppx
import ceui.refactor.viewBinding

class CommentsFragment : PixivFragment(R.layout.fragment_pixiv_list), CommentActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CommentsFragmentArgs>()
    private val dataSource by lazy { CommentsDataSource(args) }
    private val viewModel by pixivListViewModel { dataSource }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.comments)
        setUpLinearLayout(binding, viewModel)
        val dividerDecoration = BottomDividerDecoration(
            requireContext(),
            R.drawable.list_divider,
            marginLeft = 48.ppppx
        )
        binding.listView.addItemDecoration(dividerDecoration)
    }


    override fun onClickReply(replyUser: User) {
    }

    override fun onClickShowMoreReply(commentId: Long, sender: ProgressTextButton) {
        launchSuspend {
            dataSource.showMoreReply(commentId, sender)
        }
    }

    override fun onClickComment(comment: Comment) {

    }

}

interface CommentActionReceiver : UserActionReceiver {

    fun onClickReply(replyUser: User)

    fun onClickShowMoreReply(commentId: Long, sender: ProgressTextButton)

    fun onClickComment(comment: Comment)
}