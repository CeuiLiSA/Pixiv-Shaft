package ceui.pixiv.ui.comments

import android.content.Intent
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.FragmentCommentsFeedBinding
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.ProgressTextButton
import ceui.loxia.launchSuspend
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.snapshot.SnapshotCommentsFeedSource
import ceui.pixiv.snapshot.SnapshotManagerFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.blankj.utilcode.util.BarUtils

/**
 * 评论页(feeds 框架版):数据全部住在 feedViewModel（FeedViewModel<String>，见
 * [ceui.pixiv.feeds.FeedViewModel]）；发评论 / 删评论 / 展开更多回复都是本地一次性网络调用
 * （见 [CommentsComposerViewModel]），「网络结果如何编辑列表」的分支判断收口在那个 VM
 * 的 apply* 纯函数里——本 Fragment 只做编排：调网络、把返回值转手扔给
 * feedViewModel.mutateItems。卡片怎么画在 [commentCardRenderer]（CommentCardRenderer.kt）。
 * items 是否为空天然驱动 [ceui.pixiv.feeds.FeedUiState.showEmptyState]，不需要额外的
 * 空态标记：空列表发第一条评论会立刻从空态切成有内容，删光最后一条评论会自动回落空态。
 */
class CommentsFragment : FeedFragment(R.layout.fragment_comments_feed), CommentActionReceiver {

    private val args by lazy { CommentsArgs(requireArguments()) }

    private class CommentsArgs(b: Bundle) {
        val objectId: Long = b.getLong("objectId")
        val objectArthurId: Long = b.getLong("objectArthurId")
        val objectType: String = b.getString("objectType").orEmpty()
        val snapshotId: String? = b.getString(SnapshotManagerFragment.ARG_SNAPSHOT_ID)
    }

    internal val isSnapshotMode: Boolean get() = args.snapshotId != null

    private val composer by viewModels<CommentsComposerViewModel> {
        viewModelFactory {
            initializer { CommentsComposerViewModel(CommentTarget(args.objectId, args.objectType)) }
        }
    }

    override val feedViewModel by feedViewModels {
        // 零捕获约定：先取局部值，避免 mapper/initialFetch 捕获 Fragment 实例
        val objectId = args.objectId
        val objectType = args.objectType
        val illustArthurId = args.objectArthurId
        val snapshotId = args.snapshotId
        if (snapshotId != null) {
            SnapshotCommentsFeedSource(snapshotId, illustArthurId)
        } else {
            pixivFeedSource(
                initialFetch = {
                    if (objectType == ObjectType.ILLUST) {
                        Client.appApi.getIllustComments(objectId)
                    } else {
                        Client.appApi.getNovelComments(objectId)
                    }
                },
            ) { resp, phase -> mapCommentsPage(resp, illustArthurId, phase) }
        }
    }

    /** 刚发出、还没等到它上屏的顶层新评论 id——见 [applySendResult] / [onListCommitted]。 */
    private var pendingScrollHighlightCommentId: Long? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentCommentsFeedBinding.bind(view)

        // 标准 feeds toolbar 打法(对齐 PixivFragment.setUpToolbar(FragmentToolbarFeedBinding,…)):
        // 顶部状态栏高度一次性 padding,不用实时 insets(本页不再浮在模糊图上,无需每帧跟随)
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbarTitle.text = getString(
            if (isSnapshotMode) R.string.snapshot_comments_title else R.string.comments
        )

        if (isSnapshotMode) {
            binding.commentComposer.isVisible = false
            binding.bottomBar.isVisible = false
            binding.replyBanner.isVisible = false
        } else {
            CommentComposerController.attach(
                fragment = this,
                view = binding.commentComposer,
                panelRoot = binding.root,
                panelContentView = feedBinding.feedListView,
                palette = V3Palette.from(requireContext()),
                composer = composer,
                onSent = ::applySendResult,
            )
            setUpReplyBanner(binding)
        }
    }

    /** Keeps reply-only UI outside the reusable top-level/stamp composer module. */
    private fun setUpReplyBanner(binding: FragmentCommentsFeedBinding) {
        composer.replyToComment.observe(viewLifecycleOwner) { comment ->
            val visible = comment != null
            if (binding.replyBanner.isVisible != visible) {
                // 开启回复=从底部浮上来,取消回复=沉回去;ChangeBounds 顺带把因此挪位的
                // 输入框行也一起补间,不然banner一冒出/收回,下面输入框会硬生生跳一下
                TransitionManager.beginDelayedTransition(
                    binding.bottomBar,
                    TransitionSet()
                        .addTransition(Slide(Gravity.BOTTOM).addTarget(binding.replyBanner))
                        .addTransition(ChangeBounds())
                )
            }
            binding.replyBanner.isVisible = visible
            if (comment != null) {
                binding.replyBannerText.text = "${getString(R.string.string_176)} @${comment.user.name}"
            }
        }
        binding.replyBannerClose.setOnClick { composer.cancelReply() }
    }

    private fun applySendResult(result: SentComment) {
        val (parentCommentId, comment) = result
        feedViewModel.mutateItems { composer.applySentComment(it, parentCommentId, comment, args.objectArthurId) }
        // 顶层新评论固定插进 index 0(见 CommentsComposerViewModel.applySentComment);回复是挂进
        // 已有卡片内部,不在列表最前——只有顶层发送才需要「滚回最前 + 高亮」。mutateItems 只是同步
        // 改了 VM 里的 StateFlow,adapter 要等 [onListCommitted] 回调才真的吃到这份新数据——这里
        // 不能立刻滚,立刻滚会拿着 adapter 还没更新的旧 itemCount/内容起步,滚到错误位置。
        if (parentCommentId <= 0L) {
            pendingScrollHighlightCommentId = comment.id
        }
    }

    /** [ceui.pixiv.feeds.FeedFragment] 的 diff-提交完成回调:此时 adapter 才真正反映了
     * [applySendResult] 里 mutateItems 之后的那份 state,滚动目标位置才是准的。 */
    override fun onListCommitted(state: FeedUiState) {
        val targetId = pendingScrollHighlightCommentId ?: return
        val top = state.items.firstOrNull()
        if (top is CommentFeedItem && top.comment.id == targetId) {
            pendingScrollHighlightCommentId = null
            scrollToAndHighlightNewComment()
        }
    }

    /** submitList 的 diff 提交完成后,新插入的 0 号 ViewHolder 仍可能还没排布出来(布局是下一帧的
     * 事)——短延迟重试拿到手再高亮(同 [ceui.pixiv.ui.user.UserIllustFeedFragment] 的
     * highlightItemAt 打法);这里额外叠了平滑滚动的耗时,重试预算相应放宽,盖住一次完整的
     * smoothScroll。 */
    private fun scrollToAndHighlightNewComment() {
        if (view == null) return
        val listView = feedBinding.feedListView
        listView.smoothScrollToPosition(0)
        highlightItemAt(listView, 0, HIGHLIGHT_MAX_RETRIES)
    }

    private fun highlightItemAt(listView: RecyclerView, adapterPos: Int, triesLeft: Int) {
        if (view == null) return
        val holder = listView.findViewHolderForAdapterPosition(adapterPos)
        if (holder == null) {
            if (triesLeft > 0) {
                listView.postDelayed(
                    { highlightItemAt(listView, adapterPos, triesLeft - 1) },
                    HIGHLIGHT_RETRY_DELAY_MS,
                )
            }
            return
        }
        val target = holder.itemView
        target.animate().cancel()
        target.scaleX = 1f
        target.scaleY = 1f
        target.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200L)
            .withEndAction { target.animate().scaleX(1f).scaleY(1f).setDuration(200L).start() }
            .start()
    }

    private suspend fun performDelete(commentId: Long, parentCommentId: Long) {
        composer.deleteComment(commentId)
        feedViewModel.mutateItems { composer.applyDeletedComment(it, commentId, parentCommentId) }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(10.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(commentCardRenderer())
    }

    override fun onClickReply(comment: Comment, parentCommentId: Long) {
        composer.startReply(comment, parentCommentId)
    }

    override fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long) {
        launchSuspend(sender) {
            val children = composer.showMoreReply(commentId)
            feedViewModel.mutateItems { composer.applyExpandedReplies(it, commentId, children) }
        }
    }

    override fun onClickComment(comment: Comment) {

    }

    override fun onLongClickComment(anchor: View, comment: Comment, parentCommentId: Long) {
        val isOwn = SessionManager.loggedInUid == comment.user.id
        val commentText = comment.comment
        // 社交软件式长按操作:复制评论 / 回复 / 查看用户 / 删除(仅自己),统一用 V3MenuDialog
        showV3Menu("CommentMenu") {
            if (!commentText.isNullOrBlank()) {
                item(getString(R.string.string_173), R.drawable.baseline_content_copy_24) {
                    ClipBoardUtils.putTextIntoClipboard(requireContext(), commentText)
                }
                item(getString(R.string.string_translate_caption), R.drawable.ic_baseline_translate_24) {
                    translateComment(commentText)
                }
            }
            if (!isOwn) {
                item(getString(R.string.string_176), R.drawable.chat_ic_reply) {
                    onClickReply(comment, parentCommentId)
                }
            }
            item(getString(R.string.string_174), R.drawable.ic_supervisor_account_black_24dp) {
                ObjectPool.update(comment.user)
                onClickUser(comment.user.id)
            }
            if (isOwn) {
                item(getString(R.string.string_219), R.drawable.ic_delete_black_24dp) {
                    confirmDelete { launchSuspend { performDelete(comment.id, parentCommentId) } }
                }
            }
        }
    }

    override fun onClickDeleteComment(sender: ProgressTextButton, comment: Comment, parentCommentId: Long) {
        confirmDelete {
            launchSuspend(sender) {
                performDelete(comment.id, parentCommentId)
            }
        }
    }

    /**
     * 删评论是不可逆操作,卡片上的「删除」按钮又紧挨着回复,已有用户反馈手滑连着误删了好几条——
     * 两个入口(卡片按钮 / 长按菜单)都先过一道确认,确认后才真正发删除。
     */
    private fun confirmDelete(onConfirmed: () -> Unit) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(getString(R.string.string_219))
            .setMessage(getString(R.string.comment_delete_confirm))
            .addAction(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .addAction(0, getString(R.string.string_219), WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                onConfirmed()
            }
            .create()
            .show()
    }

    // classic 分支的 TemplateActivity 是裸 FragmentManager,没有 NavHostFragment——
    // findNavController()/pushFragment 必炸,直接走 Intent(master 分支才有 Navigation 那套)
    override fun onClickUser(id: Long) {
        val userIntent = Intent(requireContext(), UActivity::class.java)
        userIntent.putExtra(Params.USER_ID, id.toInt())
        startActivity(userIntent)
    }

    companion object {
        /** 新评论高亮重试预算:100ms × 30 ≈ 3s,盖住一次从列表底部到顶部的完整 smoothScroll。 */
        private const val HIGHLIGHT_MAX_RETRIES = 30
        private const val HIGHLIGHT_RETRY_DELAY_MS = 100L

        fun newInstance(
            objectId: Long,
            objectArthurId: Long,
            objectType: String
        ): CommentsFragment {
            return CommentsFragment().apply {
                arguments = Bundle().apply {
                    putLong("objectId", objectId)
                    putLong("objectArthurId", objectArthurId)
                    putString("objectType", objectType)
                }
            }
        }
    }
}

interface CommentActionReceiver : UserActionReceiver {

    fun onClickReply(comment: Comment, parentCommentId: Long)

    fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long)

    fun onClickComment(comment: Comment)

    fun onLongClickComment(anchor: View, comment: Comment, parentCommentId: Long)

    fun onClickDeleteComment(sender: ProgressTextButton, comment: Comment, parentCommentId: Long)
}
