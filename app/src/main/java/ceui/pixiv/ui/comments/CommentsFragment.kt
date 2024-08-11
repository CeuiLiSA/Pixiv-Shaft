package ceui.pixiv.ui.comments

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.CellEditingCommentBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Comment
import ceui.loxia.ProgressTextButton
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.refactor.ppppx
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding

class CommentsFragment : PixivFragment(R.layout.fragment_pixiv_list), CommentActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CommentsFragmentArgs>()
    private val dataSource by lazy { CommentsDataSource(args) }
    private val viewModel by pixivListViewModel { dataSource }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.comments)
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        setUpRefreshState(binding, viewModel)
        val dividerDecoration = BottomDividerDecoration(
            requireContext(),
            R.drawable.list_divider,
            marginLeft = 48.ppppx
        )
        binding.listView.addItemDecoration(dividerDecoration)
        binding.bottomLayout.isVisible = true
        binding.bottomLayout.background = ColorDrawable(Color.parseColor("#66000000"))
        val childBinding = DataBindingUtil.inflate<CellEditingCommentBinding>(
            layoutInflater,
            R.layout.cell_editing_comment,
            binding.bottomLayout,
            true
        )
        childBinding.lifecycleOwner = viewLifecycleOwner
        childBinding.viewModel = dataSource
        childBinding.send.setOnClick {
            launchSuspend(it) {
                dataSource.sendComment()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbarLayout.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            binding.bottomLayout.updatePadding(0, 0, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onClickReply(comment: Comment) {
        dataSource.replyToComment.value = comment
    }

    override fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long) {
        launchSuspend(sender) {
            dataSource.showMoreReply(commentId)
        }
    }

    override fun onClickComment(comment: Comment) {

    }

    override fun onClickDeleteComment(sender: ProgressTextButton, comment: Comment, parentCommentId: Long) {
        launchSuspend(sender) {
            dataSource.deleteComment(comment.id, parentCommentId)
        }
    }
}

interface CommentActionReceiver : UserActionReceiver {

    fun onClickReply(comment: Comment)

    fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long)

    fun onClickComment(comment: Comment)

    fun onClickDeleteComment(sender: ProgressTextButton, comment: Comment, parentCommentId: Long)
}