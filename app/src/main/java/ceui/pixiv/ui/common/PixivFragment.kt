package ceui.pixiv.ui.common

import android.content.Intent
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
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.UserActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.LayoutToolbarBinding
import ceui.lisa.models.ModelObject
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.ShareIllust
import ceui.lisa.view.LinearItemDecoration
import ceui.lisa.view.StaggeredGridSpacingItemDecoration
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
import ceui.loxia.clearItemDecorations
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.getHumanReadableMessage
import ceui.loxia.launchSuspend
import ceui.loxia.observeEvent
import ceui.loxia.openClashApp
import ceui.loxia.pushFragment
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.paging.CommonPagingAdapter
import ceui.pixiv.paging.PagingViewModel
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.circles.CircleFragmentArgs
import ceui.pixiv.ui.detail.ArtworkViewPagerFragmentArgs
import ceui.pixiv.ui.detail.ArtworksMap
import ceui.pixiv.ui.detail.IllustSeriesFragmentArgs
import ceui.pixiv.ui.novel.NovelSeriesActionReceiver
import ceui.pixiv.ui.novel.NovelSeriesFragmentArgs
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.ui.user.UserFragmentArgs
import ceui.pixiv.ui.web.WebFragmentArgs
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.TagsActionReceiver
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.FalsifyFooter
import com.scwang.smart.refresh.header.FalsifyHeader
import com.scwang.smart.refresh.header.MaterialHeader
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import timber.log.Timber


open class PixivFragment(layoutId: Int) : Fragment(layoutId),
    IllustCardActionReceiver,
    UserActionReceiver,
    TagsActionReceiver,
    ArticleActionReceiver,
    NovelActionReceiver,
    IllustIdActionReceiver,
    NovelSeriesActionReceiver,
    IllustSeriesActionReceiver {

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

    fun <ResultT> runOnceWithinFragmentLifecycle(
        taskId: String,
        task: () -> ResultT
    ): ResultOrNoOp<ResultT> {
        return if (fragmentViewModel.taskHasDone(taskId)) {
            ResultOrNoOp.NoOp()
        } else {
            fragmentViewModel.setTaskHasDone(taskId)
            ResultOrNoOp.Done(task())
        }
    }

    override fun onClickIllustCard(illust: Illust) {
        onClickIllust(illust.id)
    }

    override fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long) {
        launchSuspend(sender) {
            val illust = ObjectPool.get<Illust>(illustId).value
                ?: Client.appApi.getIllust(illustId).illust?.also { ObjectPool.update(it) }
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
            val novel = ObjectPool.get<Novel>(novelId).value
                ?: Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) }
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
            pushFragment(R.id.navigation_user, UserFragmentArgs(id).toBundle())
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
            pushFragment(
                R.id.navigation_circle, CircleFragmentArgs(
                    keyword = tag.name ?: "",
                    landingIndex = 2,
                ).toBundle()
            )
        } else {
            pushFragment(
                R.id.navigation_circle, CircleFragmentArgs(
                    keyword = tag.name ?: "",
                    landingIndex = 1,
                ).toBundle()
            )
        }
    }

    override fun onClickArticle(article: Article) {
        article.article_url?.let {
            pushFragment(
                R.id.navigation_web_fragment,
                WebFragmentArgs(article.article_url).toBundle()
            )
        }
    }

    override fun onClickNovel(novelId: Long) {
        pushFragment(
            R.id.navigation_viewpager_artwork,
            ArtworkViewPagerFragmentArgs(
                fragmentViewModel.fragmentUniqueId,
                novelId,
                ObjectType.NOVEL
            ).toBundle()
        )
    }

    override fun visitNovelById(novelId: Long) {
        launchSuspend {
            val novel = Client.appApi.getNovel(novelId).novel
            if (novel != null) {
                ArtworksMap.store[fragmentViewModel.fragmentUniqueId] = listOf(novelId)
                ObjectPool.update(novel)
                onClickNovel(novel.id)
            }
        }
    }

    override fun visitIllustById(illustId: Long) {
        launchSuspend {
            val illust = Client.appApi.getIllust(illustId).illust
            if (illust != null) {
                ArtworksMap.store[fragmentViewModel.fragmentUniqueId] = listOf(illustId)
                ObjectPool.update(illust)
                onClickIllust(illust.id)
            }
        }
    }

    override fun onClickIllust(illustId: Long) {
        pushFragment(
            R.id.navigation_viewpager_artwork,
            ArtworkViewPagerFragmentArgs(
                fragmentViewModel.fragmentUniqueId,
                illustId,
                ObjectType.ILLUST
            ).toBundle()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Common.showLog("onDestroy ${this::class.simpleName}")
    }

    override fun onClickNovelSeries(sender: View, series: Series) {
        pushFragment(
            R.id.navigation_novel_series, NovelSeriesFragmentArgs(series.id).toBundle()
        )
    }

    override fun onClickIllustSeries(sender: View, series: Series) {
        pushFragment(
            R.id.navigation_illust_series, IllustSeriesFragmentArgs(series.id).toBundle()
        )
    }
}

interface ViewPagerFragment

interface FitsSystemWindowFragment

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
        binding.naviBack.setOnClick {
            findNavController().popBackStack()
        }
        binding.naviMore.setOnClick {
//            requireActivity().findCurrentFragmentOrNull()?.view?.animateWiggle()
            findActionReceiverOrNull<GrayToggler>()?.toggleGrayMode()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbarLayout.updatePaddingRelative(top = insets.top)
            content.updatePadding(0, 0, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}

fun <ObjectT : ModelObject> Fragment.setUpPagedList(
    binding: FragmentPagedListBinding,
    viewModel: PagingViewModel<ObjectT>,
    listMode: Int = ListMode.STAGGERED_GRID
) {
    if (this is FitsSystemWindowFragment) {
        binding.topShadow.isVisible = true
        val params = binding.refreshLayout.layoutParams as ConstraintLayout.LayoutParams
        // 将 topToTop 设置为 parent
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        binding.refreshLayout.layoutParams = params
    }
    setUpToolbar(binding.toolbarLayout, binding.listView)
    setUpLayoutManager(binding.listView, listMode)


    val adapter = CommonPagingAdapter(viewLifecycleOwner)
    adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

    binding.listView.adapter = adapter

    val fragmentViewModel: NavFragmentViewModel by viewModels()
    val database = AppDatabase.getAppDatabase(requireContext())
    val seed = fragmentViewModel.fragmentUniqueId


    adapter.addOnPagesUpdatedListener {
        viewModel.recordType?.let { recordType ->
            val ids = database.generalDao().getAllIdsByRecordType(recordType)
            ArtworksMap.store[seed] = ids
        }
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pager.collectLatest {
                adapter.submitData(it)
            }
        }
    }

    binding.openVpn.setOnClick {
        openClashApp(requireContext())
    }
    requireNetworkStateManager().canAccessGoogle.observe(viewLifecycleOwner) { canAccessGoogle ->
        binding.openVpn.isVisible = !canAccessGoogle
        binding.errorRetryButton.isVisible = canAccessGoogle
        if (canAccessGoogle) {
            binding.errorText.text = getString(R.string.string_48)
        } else {
            binding.errorText.text = getString(R.string.no_internet_connection)
        }
        binding.errorLayout.isVisible = !canAccessGoogle
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            adapter.loadStateFlow
                .map { it.refresh }
                .collectLatest { current ->
                    binding.refreshLayout.isRefreshing = current is LoadState.Loading
                    binding.errorLayout.isVisible = current is LoadState.Error
                }
        }
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            adapter.loadStateFlow
                .map { it.refresh }
                .distinctUntilChanged()
                .scan(
                    Pair<LoadState, LoadState>(
                        LoadState.NotLoading(endOfPaginationReached = false),
                        LoadState.NotLoading(endOfPaginationReached = false)
                    )
                ) { acc, current ->
                    acc.second to current
                }
                .drop(1)
                .collectLatest { (previous, current) ->
                    if (previous is LoadState.Loading && current is LoadState.NotLoading) {
                        val previousItemCount = adapter.itemCount

                        val observer = ScrollToTopObserver(binding.listView, adapter)
                        adapter.registerAdapterDataObserver(observer)

                        if (adapter.itemCount != previousItemCount && adapter.itemCount > 0) {
                            observer.scrollToTop()
                            adapter.unregisterAdapterDataObserver(observer)
                        }
                    }
                }
        }
    }

    binding.refreshLayout.setOnRefreshListener {
        adapter.refresh()
    }
}

fun Fragment.setUpRefreshState(
    binding: FragmentPixivListBinding,
    viewModel: RefreshOwner,
    listMode: Int = ListMode.STAGGERED_GRID
) {
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

    binding.refreshLayout.setEnableRefresh(false)
    binding.refreshLayout.setEnableLoadMore(false)

    requireNetworkStateManager().canAccessGoogle.observe(viewLifecycleOwner) { canAccessGoogle ->
        binding.openVpn.isVisible = !canAccessGoogle
        binding.errorRetryButton.isVisible = canAccessGoogle
        if (canAccessGoogle) {
            binding.errorText.text = getString(R.string.string_48)
        } else {
            binding.errorText.text = getString(R.string.no_internet_connection)
        }
        binding.errorLayout.isVisible = !canAccessGoogle
    }

    viewModel.refreshState.observe(viewLifecycleOwner) { state ->
        if (state !is RefreshState.LOADING) {
            binding.refreshLayout.finishRefresh()
            binding.refreshLayout.finishLoadMore()

            binding.refreshLayout.setEnableRefresh(true)
            binding.refreshLayout.setRefreshHeader(MaterialHeader(ctx))
            binding.refreshLayout.setOnRefreshListener {
                viewModel.refresh(RefreshHint.PullToRefresh)
            }
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
        binding.cacheApplying.isVisible = state is RefreshState.FETCHING_LATEST
        val shouldShowLoading = state is RefreshState.LOADING
        binding.loadingLayout.isVisible = shouldShowLoading
        if (shouldShowLoading) {
            binding.progressCircular.showProgress()
        } else {
            binding.progressCircular.hideProgress()
        }
        binding.errorLayout.isVisible = state is RefreshState.ERROR
        binding.errorRetryButton.setOnClick {
            if (requireNetworkStateManager().canAccessGoogle.value == true) {
                viewModel.refresh(RefreshHint.ErrorRetry)
            } else {
                openClashApp(ctx)
            }
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
                Timber.d("_remoteDataSyncedEvent adapter submitList: ${viewModel::class.simpleName}")
                viewModel.prepareIdMap(fragmentViewModel.fragmentUniqueId)
            }
        }
    }

    if (viewModel is RemoteDataProvider) {
        val listView = binding.listView
        viewModel.remoteDataSyncedEvent.observeEvent(viewLifecycleOwner) {
            launchSuspend {
                if (view != null) {
                    val layoutManager = binding.listView.layoutManager
                    if (layoutManager is StaggeredGridLayoutManager) {
                        layoutManager.invalidateSpanAssignments()
                        layoutManager.scrollToPositionWithOffset(0, 0)
                    } else {
                        listView.scrollToPosition(0)
                    }
                    Timber.d("_remoteDataSyncedEvent received: ${viewModel::class.simpleName}")
                }
            }
        }
    }
}

fun Fragment.setUpLayoutManager(listView: RecyclerView, listMode: Int = ListMode.STAGGERED_GRID) {
    val ctx = requireContext()
    listView.itemAnimator = null
    listView.clearItemDecorations()
    if (listMode == ListMode.STAGGERED_GRID) {
        listView.addItemDecoration(StaggeredGridSpacingItemDecoration(4.ppppx))
        listView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
    } else if (listMode == ListMode.VERTICAL) {
        listView.layoutManager = LinearLayoutManager(ctx)
        listView.addItemDecoration(LinearItemDecoration(18.ppppx))
    } else if (listMode == ListMode.VERTICAL_COMMENT) {
        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.addItemDecoration(
            BottomDividerDecoration(
                requireContext(),
                R.drawable.list_divider,
                marginLeft = 48.ppppx
            )
        )
    } else if (listMode == ListMode.VERTICAL_TABCELL) {
        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.addItemDecoration(
            BottomDividerDecoration(
                requireContext(),
                R.drawable.list_divider,
            )
        )
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


fun FragmentActivity.findCurrentFragmentOrNull(): Fragment? {
    return try {
        val navigationFragment = supportFragmentManager.fragments
            .filterIsInstance<NavHostFragment>()
            .firstOrNull()

        val currentFragment =
            navigationFragment?.childFragmentManager?.fragments?.firstOrNull { it.isVisible }

        currentFragment?.let {
            Timber.d("Current Fragment Instance: ${it.javaClass.simpleName}")
        }

        currentFragment
    } catch (ex: Exception) {
        Timber.e(ex)
        null
    }
}


fun Fragment.shareIllust(illust: Illust) {
    launchSuspend {
        val ctx = requireContext()
        val shareText = ctx.getString(
            R.string.share_illust,
            illust.title,
            illust.user?.name,
            ShareIllust.URL_Head + illust.id
        )

        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }.also { intent ->
            startActivity(Intent.createChooser(intent, ctx.getString(R.string.share)))
        }
    }
}


const val NOVEL_URL_HEAD = "https://www.pixiv.net/novel/show.php?id="

fun Fragment.shareNovel(novel: Novel) {
    launchSuspend {
        val ctx = requireContext()
        val shareText = ctx.getString(
            R.string.share_illust,
            novel.title,
            novel.user?.name,
            NOVEL_URL_HEAD + novel.id
        )

        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }.also { intent ->
            startActivity(Intent.createChooser(intent, ctx.getString(R.string.share)))
        }
    }
}
