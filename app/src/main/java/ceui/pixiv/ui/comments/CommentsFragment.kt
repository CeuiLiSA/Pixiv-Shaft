package ceui.pixiv.ui.comments

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.User
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.PixivFragment
import ceui.pixiv.pixivListViewModel
import ceui.pixiv.setUpLinearLayout
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.refactor.ppppx
import ceui.refactor.viewBinding

class CommentsFragment : PixivFragment(R.layout.fragment_pixiv_list), CommentActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CommentsFragmentArgs>()
    private val viewModel by pixivListViewModel(
        loader = { Client.appApi.getIllustComments(args.illustId) },
        mapper = { comment -> listOf(CommentHolder(comment, args.illustArthurId)) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.naviTitle.text = getString(R.string.comments)
        setUpLinearLayout(binding, viewModel)
        val dividerDecoration = BottomDividerDecoration(requireContext(), R.drawable.list_divider, marginLeft = 48.ppppx)
        binding.listView.addItemDecoration(dividerDecoration)
    }

    override fun onClickUser(uid: Long) {
        pushFragment(R.id.navigation_user_profile, UserProfileFragmentArgs(uid).toBundle())
    }

    override fun onClickReply(replyUser: User) {
    }

    override fun onClickShowMoreReply(commentId: Long) {
        launchSuspend {
            val resp = Client.appApi.getIllustReplyComments(commentId)
            viewModel.update<CommentHolder>(commentId) { old ->
                CommentHolder(old.comment, args.illustArthurId, resp.comments)
            }
        }
    }

}

interface CommentActionReceiver {

    fun onClickUser(uid: Long)

    fun onClickReply(replyUser: User)

    fun onClickShowMoreReply(commentId: Long)
}