package ceui.pixiv.plaza.ui

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentPlazaPostDetailBinding
import ceui.lisa.network.PlazaPost
import ceui.pixiv.chat.base.launchSuspend
import com.hjq.toast.Toaster
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.session.SessionManager
import com.blankj.utilcode.util.BarUtils
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction

/**
 * 单帖详情页。从 plaza feed 卡片点进来,或从分享深链 / 通知打开。
 *
 * 结构(figma 0:24196):
 * - toolbar
 * - RecyclerView (ConcatAdapter): postHeader + commentsTitle + (empty | comments)
 * - bottom_bar:输入框 + 发送
 *
 * - init:用 ShaftApiV2Client.cachedPlazaPost(id) 提供快照(从 feed 进来一般已缓存),
 *   同时 launch GET /posts/:id 拉权威版本(带 viewer sig 获取 liked_by_viewer)
 * - 404:显示 plaza_post_gone 占位
 * - 自己的帖子:卡片右上角 ⋯ 菜单可弹出删除(由 bindPlazaPostCard 接管)
 */
class PlazaPostDetailFragment : Fragment(R.layout.fragment_plaza_post_detail) {

    private val binding by viewBinding(FragmentPlazaPostDetailBinding::bind)
    private val postId: Long by lazy { requireArguments().getLong(EXTRA_POST_ID, 0L) }
    private val viewModel by viewModels { PlazaPostDetailViewModel(postId) }

    private lateinit var headerAdapter: PlazaPostHeaderAdapter
    private lateinit var commentsTitleAdapter: PlazaCommentsTitleAdapter
    private lateinit var commentsEmptyAdapter: PlazaCommentsEmptyAdapter
    private lateinit var commentsAdapter: PlazaCommentAdapter

    /**
     * TemplateActivity 在 manifest 里声明 `windowSoftInputMode="adjustPan"`,
     * 评论输入触发软键盘时会把整窗向上推 —— toolbar 顶出屏幕、底部留黑边。
     * 这里跟 [ceui.pixiv.chat.ui.DemoChatListFragment] 一样:onResume 切到
     * adjustResize,onPause 还原,只影响本页其他 fragment 行为不变。
     *
     * 配合 [setupInsets] 把 IME inset 消费到 root padding 上,bottom_bar
     * (锚 parent.bottom) 就会跟着键盘抬起,toolbar 留在原位。
     */
    private var previousSoftInputMode: Int = INVALID_SOFT_INPUT_MODE

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // brand 色 + status bar top padding 必须 runtime —— M3 父 overlay 下
        // XML 的 ?attr/colorPrimary 解出 baseline tone(不是用户主题色),
        // fitsSystemWindows 又会被 EdgeToEdge 套进 nav inset 把 toolbar 撑高。
        binding.toolbar.setBackgroundColor(Color.parseColor(Shaft.getThemeColor()))
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        setupInsets()
        setupRecycler()
        setupInputBar()

        launchSuspend {
            viewModel.state.collect { s ->
                renderState(s)
            }
        }
        launchSuspend {
            viewModel.events.collect { ev ->
                when (ev) {
                    is PlazaPostDetailViewModel.Event.Toast -> Toaster.showShort(ev.message)
                    PlazaPostDetailViewModel.Event.DeletedAndClose -> requireActivity().finish()
                    PlazaPostDetailViewModel.Event.CommentSent -> {
                        binding.commentInput.text?.clear()
                        binding.recyclerView.scrollToPosition(0)
                    }
                }
            }
        }
    }

    private fun setupInsets() {
        // EdgeToEdge 下 root 没 fitsSystemWindows,IME / navBar inset 必须手动消费。
        // 把 max(ime, navBar) 加到 root.paddingBottom:
        //  - 键盘关:padding = navBar,bottom_bar 浮在 navBar 之上
        //  - 键盘开:padding = IME 高度,bottom_bar 被 root 撑起跟随键盘上推
        // (toolbar 顶部锚不受 bottom padding 影响,留在原位)
        // 必须配合 onResume 切 adjustResize —— adjustPan 会让整窗平移,把这套
        // inset 消费方案绕过去。
        val insetTypes = WindowInsetsCompat.Type.ime() or
            WindowInsetsCompat.Type.navigationBars()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(insetTypes).bottom)
            insets
        }
    }

    private fun setupRecycler() {
        headerAdapter = PlazaPostHeaderAdapter(
            selfUid = SessionManager.loggedInUid,
            onMore = { post, anchor -> showMoreMenu(post, anchor) },
        )
        commentsTitleAdapter = PlazaCommentsTitleAdapter()
        commentsEmptyAdapter = PlazaCommentsEmptyAdapter()
        commentsAdapter = PlazaCommentAdapter(postAuthorUid = 0L)
            // postAuthorUid 在 post 加载后通过 rebuildCommentsAdapter 设;留 0L 占位

        val concat = ConcatAdapter(
            headerAdapter, commentsTitleAdapter, commentsEmptyAdapter, commentsAdapter,
        )
        val layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = concat
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val total = layoutManager.itemCount
                val last = layoutManager.findLastVisibleItemPosition()
                if (last >= total - 4) viewModel.loadMoreComments()
            }
        })
    }

    private fun setupInputBar() {
        // EditText textChanged → 控制 send.enabled。空文本或正在发送时禁用。
        binding.commentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                refreshSendEnabled()
            }
        })
        binding.commentInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                trySend(); true
            } else false
        }
        binding.btnSend.setOnClickListener { trySend() }
    }

    private fun trySend() {
        val text = binding.commentInput.text?.toString().orEmpty()
        viewModel.postComment(requireContext(), text)
    }

    private fun refreshSendEnabled() {
        val s = viewModel.state.value
        val hasText = (binding.commentInput.text?.toString()?.trim()?.isNotEmpty() == true)
        binding.btnSend.isEnabled = hasText && !s.isSendingComment
        binding.btnSend.alpha = if (binding.btnSend.isEnabled) 1f else 0.4f
    }

    private var lastBoundAuthorUid: Long = -1L

    private fun renderState(s: PlazaPostDetailViewModel.UiState) {
        if (s.isGone) {
            binding.recyclerView.isVisible = false
            binding.bottomBar.isVisible = false
            binding.goneText.isVisible = true
            return
        }
        binding.goneText.isVisible = false
        binding.recyclerView.isVisible = true
        binding.bottomBar.isVisible = true

        val post = s.post
        if (post != null) {
            // 帖子作者 uid 变化(首次加载)时重建 comments adapter,因为 postAuthorUid
            // 是 final ctor 参数 —— 重建后才能正确判定「作者」标。
            if (post.uid != lastBoundAuthorUid) {
                lastBoundAuthorUid = post.uid
                commentsAdapter = PlazaCommentAdapter(postAuthorUid = post.uid)
                val concat = ConcatAdapter(
                    headerAdapter, commentsTitleAdapter, commentsEmptyAdapter, commentsAdapter,
                )
                binding.recyclerView.adapter = concat
            }
            headerAdapter.submitPost(post)
        }

        commentsTitleAdapter.setTotal(s.commentsTotal)
        commentsEmptyAdapter.setVisible(
            !s.commentsLoading && s.comments.isEmpty() && s.post != null
        )
        commentsAdapter.submitList(s.comments)
        refreshSendEnabled()
    }

    private fun showMoreMenu(post: PlazaPost, anchor: View) {
        // 这里只有「删除」一项 —— 跟 feed 一致。MVP 不上 PopupMenu 实现,
        // 直接走 QMUI 的确认对话框。
        QMUIDialog.MessageDialogBuilder(requireContext())
            .setMessage(R.string.plaza_delete_confirm)
            .addAction(R.string.plaza_delete_cancel) { d, _ -> d.dismiss() }
            .addAction(
                0, R.string.plaza_delete_confirm_yes, QMUIDialogAction.ACTION_PROP_NEGATIVE
            ) { d, _ ->
                d.dismiss()
                viewModel.delete(requireContext(), SessionManager.loggedInUid)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        val window = requireActivity().window
        previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onPause() {
        super.onPause()
        if (previousSoftInputMode != INVALID_SOFT_INPUT_MODE) {
            requireActivity().window.setSoftInputMode(previousSoftInputMode)
            previousSoftInputMode = INVALID_SOFT_INPUT_MODE
        }
    }

    companion object {
        const val EXTRA_POST_ID = "plaza_post_id"

        /** Sentinel — softInputMode 是打包的 int,-1 永远不是合法组合。 */
        private const val INVALID_SOFT_INPUT_MODE = -1

        fun newInstance(postId: Long): PlazaPostDetailFragment {
            return PlazaPostDetailFragment().apply {
                arguments = Bundle().apply { putLong(EXTRA_POST_ID, postId) }
            }
        }
    }
}
