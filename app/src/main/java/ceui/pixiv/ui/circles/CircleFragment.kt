package ceui.pixiv.ui.circles

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.databinding.FragmentCircleBinding
import ceui.lisa.utils.Common
import ceui.loxia.CircleResponse
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.search.SearchIlllustMangaFragment
import ceui.pixiv.ui.search.SearchNovelFragment
import ceui.pixiv.ui.search.SearchUserFragment
import ceui.pixiv.ui.search.SearchViewModel
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.ppppx
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.blankj.utilcode.util.BarUtils
import com.scwang.smart.refresh.header.MaterialHeader

class CircleFragment : TitledViewPagerFragment(R.layout.fragment_circle) {

    private val binding by viewBinding(FragmentCircleBinding::bind)
    private val args by navArgs<CircleFragmentArgs>()
    private val searchViewModel by constructVM({ args.keyword }) { word ->
        SearchViewModel(word)
    }
    private val viewModel by pixivValueViewModel {
        val responseStore = ResponseStore(
            { "circle-detail-${args.keyword}" },
            expirationTimeMillis = 1800L,
            CircleResponse::class.java,
            dataLoader = {
                Client.webApi.getCircleDetail(args.keyword)
            }
        )
        responseStore.retrieveData()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchViewModel
        binding.refreshLayout.setOnRefreshListener {
            viewModel.refresh(RefreshHint.PullToRefresh)
            searchViewModel.triggerAllRefreshEvent()
        }
        binding.naviBack.setOnClick {
            findNavController().popBackStack()
        }
        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            binding.refreshLayout.isEnabled = verticalOffset == 0
            val totalScrollRange = appBarLayout.totalScrollRange
            if (totalScrollRange == 0) {
                return@addOnOffsetChangedListener
            }

            val percentage = (Math.abs(verticalOffset) / totalScrollRange.toFloat())

            if (percentage == 0f) {
                // AppBarLayout fully expanded
                Common.showLog("asdadsas onAppBarExpanded ${totalScrollRange}, ${verticalOffset}")
            } else if (percentage == 100f) {
                // AppBarLayout fully collapsed
                Common.showLog("asdadsas onAppBarCollapsed ${totalScrollRange}, ${verticalOffset}")
            } else {
                // AppBarLayout partially collapsed
                Common.showLog("asdadsas onAppBarPartiallyCollapsed ${totalScrollRange}, ${verticalOffset}, ${percentage}")
            }

            binding.headerContent.alpha = 1F - percentage
        }
        binding.circle = viewModel.result
        binding.refreshLayout.setEnableRefresh(true)
        binding.refreshLayout.setEnableLoadMore(false)
        viewModel.refreshState.observe(viewLifecycleOwner) { state ->
            if (state is RefreshState.LOADING) {
                binding.refreshLayout.finishRefresh()
            }
        }
        binding.circle = viewModel.result
        viewModel.result.observe(viewLifecycleOwner) { circle ->
            binding.worksCount.text = "${circle.body?.total ?: 0}个作品"
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top - 10.ppppx
            }
            binding.headerContent.updatePaddingRelative(top = insets.top + BarUtils.getActionBarHeight())
            windowInsets
        }

        val adapter = SmartFragmentPagerAdapter(listOf(
            PagedFragmentItem(
                builder = { CircleInfoFragment() },
                titleLiveData = getTitleLiveData(0).apply {
                    value = getString(R.string.about_app)
                }
            ),
            PagedFragmentItem(
                builder = {
                    SearchIlllustMangaFragment()
                },
                titleLiveData = getTitleLiveData(1).apply {
                    value = getString(R.string.string_136)
                }
            ),
            PagedFragmentItem(
                builder = {
                    SearchNovelFragment()
                },
                titleLiveData = getTitleLiveData(2).apply {
                    value = getString(R.string.type_novel)
                }
            ),
            PagedFragmentItem(
                builder = {
                    SearchUserFragment()
                },
                titleLiveData = getTitleLiveData(3).apply {
                    value = getString(R.string.type_user)
                }
            )
        ), this)
        binding.circleViewPager.adapter = adapter
        binding.tabLayoutList.setUpWith(binding.circleViewPager, binding.slidingCursor, viewLifecycleOwner) {

        }
    }
}