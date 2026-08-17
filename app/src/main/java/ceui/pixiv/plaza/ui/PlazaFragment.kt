package ceui.pixiv.plaza.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentPlazaBinding
import ceui.pixiv.chat.base.PagingFooterAdapter
import ceui.pixiv.chat.base.PagingState
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.chat.core.AppError
import ceui.lisa.network.PlazaPost
import ceui.pixiv.session.SessionManager
import ceui.pixiv.widgets.LoadMoreScrollListener
import ceui.pixiv.widgets.applyV3RefreshTheme
import com.blankj.utilcode.util.BarUtils
import com.hjq.toast.Toaster
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction

/**
 * 广场。Toolbar 右上 + → 进发帖页;右下 FAB 备份入口 (移动端 reach 友好)。
 *
 * 列表用 ConcatAdapter(feedAdapter, footerAdapter):
 *   - feedAdapter:数据
 *   - footerAdapter:loading more / error 状态
 * 这套跟 chat 一致,UI 一致 + 复用 chat 的状态机。
 *
 * 删帖入口在每条卡片右上 ⋯ 菜单里,且仅自己发的帖子才显示 (卡片 onMore
 * 时判断 post.uid == SessionManager.loggedInUid)。
 */
class PlazaFragment : Fragment(R.layout.fragment_plaza) {

    private val binding by viewBinding(FragmentPlazaBinding::bind)
    private val viewModel by viewModels { PlazaViewModel() }

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
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_plaza_compose) {
                openCompose(); true
            } else false
        }
        binding.fabCompose.setOnClickListener { openCompose() }

        // 适配 nav bar / 手势条: FAB marginBottom = XML 基础值 + bar inset,
        // RecyclerView paddingBottom 同步往下加,让最后一条 plaza 卡片不被
        // FAB 或者 nav bar 挡住。
        val fabBaseMarginBottom = (binding.fabCompose.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val rvBasePaddingBottom = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            (binding.fabCompose.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = fabBaseMarginBottom + nav
                binding.fabCompose.layoutParams = this
            }
            binding.recyclerView.updatePadding(bottom = rvBasePaddingBottom + nav)
            insets
        }

        // 列表 + 分页
        val feedAdapter = PlazaFeedAdapter(
            selfUid = SessionManager.loggedInUid,
            onMore = ::onPostMore,
            onCardClick = ::openDetail,
        )
        val footerAdapter = PagingFooterAdapter().apply {
            onRetry = { viewModel.loadMore(requireContext()) }
        }
        val concatAdapter = androidx.recyclerview.widget.ConcatAdapter(feedAdapter, footerAdapter)

        val layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = concatAdapter
        // load-more 走 RecyclerView 滚动监听;SwipeRefreshLayout 只管下拉刷新,
        // 上拉翻页没有手势入口,重入由 viewModel.loadMore 自己守卫。
        binding.recyclerView.addOnScrollListener(
            LoadMoreScrollListener({ viewModel.loadMore(requireContext()) })
        )

        binding.refreshLayout.applyV3RefreshTheme()
        binding.refreshLayout.setOnRefreshListener {
            viewModel.load(requireContext(), isSwipeRefresh = true)
        }
        binding.errorRetry.setOnClickListener { viewModel.load(requireContext()) }

        // 状态订阅 + 事件
        var previousFirstId: Long? = null
        launchSuspend {
            viewModel.state.collect { s ->
                // Prepend 检测:旧 top 还在新列表中、但已不在 index 0 → 列表头插入了新条目
                // (发帖成功 / 别人发新帖后 plaza 同步进来都会触发)。命中时 submitList
                // commit 后强制滚到 0,否则 RecyclerView 默认会把视口锚在旧 top,新帖
                // 被推到屏外用户看不见。
                val currentFirstId = s.items.firstOrNull()?.id
                val didPrepend = previousFirstId != null &&
                    currentFirstId != null &&
                    currentFirstId != previousFirstId &&
                    s.items.indexOfFirst { it.id == previousFirstId } > 0
                previousFirstId = currentFirstId
                if (didPrepend) {
                    feedAdapter.submitList(s.items) {
                        if (view.isAttachedToWindow) binding.recyclerView.scrollToPosition(0)
                    }
                } else {
                    feedAdapter.submitList(s.items)
                }
                val whiteScreen = !s.isInitialLoading && s.items.isEmpty()
                binding.emptyView.isVisible = whiteScreen && s.initialError == null
                binding.errorLayout.isVisible = whiteScreen && s.initialError != null
                if (s.initialError != null) {
                    binding.errorText.text = getString(R.string.plaza_load_failed, s.initialError)
                }
                binding.refreshLayout.isRefreshing = s.isRefreshing
                footerAdapter.setPagingState(
                    when (val p = s.paging) {
                        PlazaViewModel.PlazaPagingState.Idle -> PagingState.Idle
                        PlazaViewModel.PlazaPagingState.LoadingMore -> PagingState.LoadingMore
                        PlazaViewModel.PlazaPagingState.EndReached -> PagingState.EndReached
                        is PlazaViewModel.PlazaPagingState.Error ->
                            // wrap into AppError.Unknown so footerAdapter renders the message
                            PagingState.Error(AppError.Unknown(p.message))
                    }
                )
            }
        }
        launchSuspend {
            viewModel.events.collect { ev ->
                when (ev) {
                    is PlazaViewModel.Event.Toast ->
                        Toaster.showShort(ev.message)
                }
            }
        }

        // 首次进来加载
        if (savedInstanceState == null) viewModel.load(requireContext())
    }

    private fun openDetail(post: PlazaPost) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Plaza帖子详情")
        intent.putExtra(PlazaPostDetailFragment.EXTRA_POST_ID, post.id)
        startActivity(intent)
    }

    private fun openCompose() {
        if (SessionManager.loggedInUid <= 0L) {
            Toaster.showShort(R.string.plaza_login_required)
            return
        }
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "发帖")
        startActivity(intent)
    }

    private fun onPostMore(post: PlazaPost, anchor: View) {
        // 只有自己的帖子才会到这里 (PlazaFeedAdapter 已按 selfUid 隐藏 ⋯ 按钮)。
        // MVP 只有「删除」一项,QMUI 风格统一全 app 弹窗。
        if (post.uid != SessionManager.loggedInUid) return
        WitDialog.MessageDialogBuilder(requireContext())
            .setMessage(R.string.plaza_delete_confirm)
            .addAction(R.string.plaza_delete_cancel) { d, _ -> d.dismiss() }
            .addAction(
                0, R.string.plaza_delete_confirm_yes, WitDialogAction.ACTION_PROP_NEGATIVE
            ) { d, _ ->
                d.dismiss()
                viewModel.deletePost(requireContext(), post, SessionManager.loggedInUid)
            }
            .show()
    }
}
