package ceui.pixiv.ui.comments

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.databinding.DataBindingUtil
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.CellEditingCommentBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Comment
import ceui.loxia.ProgressTextButton
import ceui.loxia.hideKeyboard
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.delay

class CommentsFragment : PixivFragment(R.layout.fragment_pixiv_list), CommentActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CommentsFragmentArgs>()
    private val viewModel by pixivListViewModel { CommentsDataSource(args) }
    private val dataSource: CommentsDataSource by lazy { viewModel.typedDataSource() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.comments)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_COMMENT)
        binding.bottomLayout.isVisible = true
        binding.bottomLayout.background = ColorDrawable(Color.parseColor("#66000000"))
        val childBinding = DataBindingUtil.inflate<CellEditingCommentBinding>(
            layoutInflater,
            R.layout.cell_editing_comment,
            binding.bottomLayout,
            true
        )
        // 设置根布局的点击监听
        binding.touchOutside.setOnTouchListener { _, _ ->
            // 隐藏键盘
            hideKeyboard()
            false
        }
        childBinding.lifecycleOwner = viewLifecycleOwner
        childBinding.viewModel = dataSource
        childBinding.send.setOnClick {
            launchSuspend(it) {
                dataSource.sendComment()
                delay(50)
                hideKeyboard()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime()) // 获取输入法的 insets
            val systemBarsInsets =
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()) // 获取系统栏的 insets

            // 更新 Toolbar 的顶部 padding
            binding.toolbarLayout.root.updatePaddingRelative(top = systemBarsInsets.top)

            // 确定底部 inset
            binding.touchOutside.isVisible = imeInsets.bottom > 0
            val bottomInsets =
                if (imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
            binding.bottomLayout.updatePadding(bottom = bottomInsets)

            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onClickReply(comment: Comment, parentCommentId: Long) {
        dataSource.replyToComment.value = comment
        dataSource.replyParentComment.value = parentCommentId
    }

    override fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long) {
        launchSuspend(sender) {
            dataSource.showMoreReply(commentId)
        }
    }

    override fun onClickComment(comment: Comment) {

    }

    override fun onClickDeleteComment(
        sender: ProgressTextButton,
        comment: Comment,
        parentCommentId: Long
    ) {
        launchSuspend(sender) {
            dataSource.deleteComment(comment.id, parentCommentId)
        }
    }

    companion object {
        fun newInstance(
            objectId: Long,
            objectArthurId: Long,
            objectType: String
        ): CommentsFragment {
            return CommentsFragment().apply {
                arguments = CommentsFragmentArgs(objectId, objectArthurId, objectType).toBundle()
            }
        }
    }
}

interface CommentActionReceiver : UserActionReceiver {

    fun onClickReply(comment: Comment, parentCommentId: Long)

    fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long)

    fun onClickComment(comment: Comment)

    fun onClickDeleteComment(sender: ProgressTextButton, comment: Comment, parentCommentId: Long)
}