package ceui.pixiv.ui.common

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.UserActivity
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.LayoutToolbarBinding
import ceui.lisa.models.ObjectSpec
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Article
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.ProgressIndicator
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.Series
import ceui.loxia.Tag
import ceui.loxia.getHumanReadableMessage
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.circles.CircleFragmentArgs
import ceui.pixiv.ui.detail.ArtworkViewPagerFragmentArgs
import ceui.pixiv.ui.novel.NovelSeriesActionReceiver
import ceui.pixiv.ui.novel.NovelSeriesFragmentArgs
import ceui.pixiv.ui.novel.NovelTextFragmentArgs
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.pixiv.ui.web.WebFragmentArgs
import ceui.pixiv.widgets.TagsActionReceiver
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.FalsifyFooter
import com.scwang.smart.refresh.header.FalsifyHeader
import com.scwang.smart.refresh.header.MaterialHeader
import timber.log.Timber


open class PixivFragment(layoutId: Int) : Fragment(layoutId), IllustCardActionReceiver,
    UserActionReceiver, TagsActionReceiver, ArticleActionReceiver, NovelActionReceiver, IllustIdActionReceiver, NovelSeriesActionReceiver {

    protected val fragmentViewModel: NavFragmentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Common.showLog("onCreate ${this::class.simpleName}")
    }

    open fun onViewFirstCreated(view: View) {

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (fragmentViewModel.viewCreatedTime.value == null) {
            onViewFirstCreated(view)
        }

        fragmentViewModel.viewCreatedTime.value = System.currentTimeMillis()
    }

    override fun onClickIllustCard(illust: Illust) {
        onClickIllust(illust.id)
    }

    override fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long) {
        launchSuspend(sender) {
            val illust = ObjectPool.get<Illust>(illustId).value ?: Client.appApi.getIllust(illustId).illust?.also { ObjectPool.update(it) }
            if (illust != null) {
                if (illust.is_bookmarked == true) {
                    Client.appApi.removeBookmark(illustId)
                    ObjectPool.update(
                        illust.copy(
                            is_bookmarked = false,
                            total_bookmarks = illust.total_bookmarks?.minus(1)
                        )
                    )
                    Common.showToast(getString(R.string.cancel_like_illust))
                } else {
                    Client.appApi.postBookmark(illustId)
                    ObjectPool.update(
                        illust.copy(
                            is_bookmarked = true,
                            total_bookmarks = illust.total_bookmarks?.plus(1)
                        )
                    )
                    Common.showToast(getString(R.string.like_novel_success_public))
                }
            }
        }
    }

    override fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long) {
        launchSuspend(sender) {
            val novel = ObjectPool.get<Novel>(novelId).value ?: Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) }
            if (novel != null) {
                if (novel.is_bookmarked == true) {
                    Client.appApi.removeNovelBookmark(novelId)
                    ObjectPool.update(
                        novel.copy(
                            is_bookmarked = false,
                            total_bookmarks = novel.total_bookmarks?.minus(1)
                        )
                    )
                    Common.showToast(getString(R.string.cancel_like_illust))
                } else {
                    Client.appApi.addNovelBookmark(novelId, Params.TYPE_PUBLIC)
                    ObjectPool.update(
                        novel.copy(
                            is_bookmarked = true,
                            total_bookmarks = novel.total_bookmarks?.plus(1)
                        )
                    )
                    Common.showToast(getString(R.string.like_novel_success_public))
                }
            }
        }
    }

    override fun onClickUser(id: Long) {
        try {
            pushFragment(R.id.navigation_user_profile, UserProfileFragmentArgs(id).toBundle())
        } catch (ex: Exception) {
            Timber.e(ex)
            val userIntent = Intent(
                requireContext(),
                UserActivity::class.java
            )
            userIntent.putExtra(
                Params.USER_ID, id.toInt()
            )
            startActivity(userIntent)
        }
    }

    override fun onClickTag(tag: Tag, objectType: String) {
        if (objectType == ObjectType.NOVEL) {

        } else {
//            pushFragment(R.id.navigation_search_viewpager, SearchViewPagerFragmentArgs(
//                keyword = tag.name ?: "",
//            ).toBundle())
            pushFragment(R.id.navigation_circle, CircleFragmentArgs(
                keyword = tag.name ?: "",
            ).toBundle())
        }
    }

    override fun onClickArticle(article: Article) {
        article.article_url?.let {
            pushFragment(R.id.navigation_web_fragment, WebFragmentArgs(article.article_url).toBundle())
        }
    }

    override fun onClickNovel(novelId: Long) {
        pushFragment(
            R.id.navigation_viewpager_artwork,
            ArtworkViewPagerFragmentArgs(fragmentViewModel.fragmentUniqueId, novelId, ObjectType.NOVEL).toBundle()
        )
    }

    override fun onClickIllust(illustId: Long) {
        pushFragment(
            R.id.navigation_viewpager_artwork,
            ArtworkViewPagerFragmentArgs(fragmentViewModel.fragmentUniqueId, illustId, ObjectType.ILLUST).toBundle()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Common.showLog("onDestroy ${this::class.simpleName}")
    }

    override fun onClickSeries(sender: View, series: Series) {
        pushFragment(
            R.id.navigation_novel_series, NovelSeriesFragmentArgs(series.id).toBundle()
        )
    }
}

interface ViewPagerFragment {

}

interface FitsSystemWindowFragment {

}

interface ITitledViewPager : ViewPagerFragment {
    fun getTitleLiveData(index: Int): MutableLiveData<String>
}

interface HomeTabContainer : ViewPagerFragment {
    fun bottomExtraSpacing(): Int = 100.ppppx
}

fun Fragment.setUpToolbar(binding: LayoutToolbarBinding, content: ViewGroup) {
    val parentFrag = parentFragment
    if (parentFrag is ViewPagerFragment) {
        binding.toolbarLayout.isVisible = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (parentFrag is HomeTabContainer) {
                content.updatePadding(0, 0, 0, insets.bottom + parentFrag.bottomExtraSpacing())
            } else {
                content.updatePadding(0, 0, 0, insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    } else {
        binding.toolbarLayout.isVisible = true
        if (activity is HomeActivity) {
            binding.naviBack.setOnClick {
                findNavController().popBackStack()
            }
        } else {
            binding.toolbarLayout.background = ColorDrawable(
                Common.resolveThemeAttribute(requireContext(), androidx.appcompat.R.attr.colorPrimary)
            )
            binding.naviBack.setOnClick {
                requireActivity().finish()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbarLayout.updatePaddingRelative(top = insets.top)
            content.updatePadding(0, 0, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}

fun Fragment.setUpRefreshState(binding: FragmentPixivListBinding, viewModel: RefreshOwner, listMode: Int = ListMode.STAGGERED_GRID) {
    if (this is FitsSystemWindowFragment) {
        binding.topShadow.isVisible = true
        val params = binding.refreshLayout.layoutParams as ConstraintLayout.LayoutParams
        // 将 topToTop 设置为 parent
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        binding.refreshLayout.layoutParams = params
    }
    setUpToolbar(binding.toolbarLayout, binding.listView)
    setUpLayoutManager(binding.listView, listMode)
    val ctx = requireContext()
    binding.refreshLayout.setRefreshHeader(MaterialHeader(ctx))
    binding.refreshLayout.setOnRefreshListener {
        viewModel.refresh(RefreshHint.PullToRefresh)
    }
    viewModel.refreshState.observe(viewLifecycleOwner) { state ->
        if (state !is RefreshState.LOADING) {
            binding.refreshLayout.finishRefresh()
            binding.refreshLayout.finishLoadMore()
        }
        binding.emptyLayout.isVisible = state is RefreshState.LOADED && !state.hasContent
        if (state is RefreshState.LOADED) {
            binding.refreshLayout.setEnableLoadMore(true)
            if (state.hasNext) {
                binding.refreshLayout.setRefreshFooter(ClassicsFooter(ctx))
                if (viewModel is LoadMoreOwner) {
                    binding.refreshLayout.setOnLoadMoreListener {
                        viewModel.loadMore()
                    }
                } else {
                    binding.refreshLayout.setRefreshFooter(FalsifyFooter(ctx))
                }
            } else {
                binding.refreshLayout.setRefreshFooter(FalsifyFooter(ctx))
            }
        } else {
            binding.refreshLayout.setEnableLoadMore(false)
        }
        val shouldShowLoading = state is RefreshState.LOADING && (
                state.refreshHint == RefreshHint.InitialLoad ||
                        state.refreshHint == RefreshHint.ErrorRetry
                )
        binding.loadingLayout.isVisible = shouldShowLoading
        if (shouldShowLoading) {
            binding.progressCircular.playAnimation()
        } else {
            binding.progressCircular.cancelAnimation()
        }
        binding.errorLayout.isVisible = state is RefreshState.ERROR
        binding.errorRetryButton.setOnClick {
            viewModel.refresh(RefreshHint.ErrorRetry)
        }
        if (state is RefreshState.ERROR) {
            binding.errorText.text = state.exception.getHumanReadableMessage(ctx)
        }
    }
    if (viewModel is HoldersContainer) {
        val fragmentViewModel: NavFragmentViewModel by viewModels()
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.adapter = adapter
        viewModel.holders.observe(viewLifecycleOwner) { holders ->
            adapter.submitList(holders) {
                viewModel.prepareIdMap(fragmentViewModel.fragmentUniqueId)
            }
        }
    }
}

fun Fragment.setUpLayoutManager(listView: RecyclerView, listMode: Int = ListMode.STAGGERED_GRID) {
    val ctx = requireContext()
    listView.itemAnimator = null
    if (listMode == ListMode.STAGGERED_GRID) {
        listView.addItemDecoration(SpacesItemDecoration(4.ppppx))
        listView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
    } else if (listMode == ListMode.VERTICAL) {
        listView.layoutManager = LinearLayoutManager(ctx)
        listView.addItemDecoration(LinearItemDecoration(18.ppppx))
    } else if (listMode == ListMode.VERTICAL_COMMENT) {
        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.addItemDecoration(BottomDividerDecoration(
            requireContext(),
            R.drawable.list_divider,
            marginLeft = 48.ppppx
        ))
    } else if (listMode == ListMode.VERTICAL_NO_MARGIN) {
        listView.layoutManager = LinearLayoutManager(ctx)
    } else if (listMode == ListMode.GRID) {
        listView.layoutManager = GridLayoutManager(ctx, 3)
    } else if (listMode == ListMode.GRID_AND_SECTION_HEADER) {
        listView.layoutManager = GridLayoutManager(ctx, 3).apply {
            spanSizeLookup = object : SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (listView.adapter?.getItemViewType(position) == RedSectionHeaderHolder::class.java.hashCode()) {
                        3
                    } else {
                        1
                    }
                }
            }
        }
    }
}

fun Fragment.setUpCustomAdapter(binding: FragmentPixivListBinding, listMode: Int): CommonAdapter {
    val adapter = CommonAdapter(viewLifecycleOwner)
    binding.listView.adapter = adapter
    binding.refreshLayout.setRefreshHeader(FalsifyHeader(requireContext()))
    binding.refreshLayout.setRefreshFooter(FalsifyFooter(requireContext()))
    setUpToolbar(binding.toolbarLayout, binding.listView)
    setUpLayoutManager(binding.listView, listMode)
    return adapter
}
