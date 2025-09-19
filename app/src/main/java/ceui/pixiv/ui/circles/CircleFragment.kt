package ceui.pixiv.ui.circles

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.map
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentCircleBinding
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustIdActionReceiver
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.search.SearchIlllustMangaFragment
import ceui.pixiv.ui.search.SearchNovelFragment
import ceui.pixiv.ui.search.SearchUserFragment
import ceui.pixiv.ui.search.SearchViewModel
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.setUpWith
import ceui.pixiv.widgets.setupVerticalAwareViewPager2
import com.blankj.utilcode.util.BarUtils
import com.scwang.smart.refresh.header.MaterialHeader

class CircleFragment : TitledViewPagerFragment(R.layout.fragment_circle) {

    private val binding by viewBinding(FragmentCircleBinding::bind)
    private val args by navArgs<CircleFragmentArgs>()
    private val searchViewModel by constructVM({ args.keyword }) { word ->
        SearchViewModel(false, word)
    }
    private val viewModel by pixivValueViewModel(
        argsProducer = { args.keyword },
        repositoryProducer = { keyword ->
            CircleRepository(keyword, createResponseStore({ "circle-detail-${args.keyword}" }))
        })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchViewModel
        setupVerticalAwareViewPager2(binding.circleViewPager)
        val tabLayoutList = binding.tabLayoutList
        binding.naviTitle.text = args.keyword
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
            binding.headerContent.alpha = 1F - percentage
            binding.naviTitle.isVisible = (percentage == 1F)
        }
        binding.circle = viewModel.result.map { it.data }
        binding.refreshLayout.setEnableRefresh(true)
        binding.refreshLayout.setEnableLoadMore(false)
        viewModel.refreshState.observe(viewLifecycleOwner) { state ->
            if (state is RefreshState.LOADING) {
                if (state.refreshHint == RefreshHint.InitialLoad) {
                    binding.circleRootLayout.isVisible = false
                    binding.progressCircular.isVisible = true
                    binding.progressCircular.playAnimation()
                }
                binding.refreshLayout.finishRefresh()
            } else {
                binding.progressCircular.cancelAnimation()
                binding.progressCircular.isVisible = false
            }
            if (state is RefreshState.LOADED) {
                binding.circleRootLayout.isVisible = true
                tabLayoutList.setUpWith(
                    binding.circleViewPager, binding.slidingCursor, viewLifecycleOwner
                ) {
                }
            }
        }
        viewModel.result.observe(viewLifecycleOwner) { loadResult ->
            val circle = loadResult?.data ?: return@observe
            binding.worksCount.text = "${circle.body?.total ?: 0}个作品"
            binding.tagIcon.setOnClick {
                circle?.body?.meta?.pixpedia?.illust?.id?.let { id ->
                    it.findActionReceiverOrNull<IllustIdActionReceiver>()?.onClickIllust(id)
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top - 10.ppppx
            }
            binding.headerContent.updatePaddingRelative(top = insets.top + BarUtils.getActionBarHeight())
            windowInsets
        }

        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = { CircleInfoFragment() }, initialTitle = getString(R.string.about_app)
                ), PagedFragmentItem(
                    builder = {
                        SearchIlllustMangaFragment()
                    }, initialTitle = getString(R.string.string_136)
                ), PagedFragmentItem(
                    builder = {
                        SearchNovelFragment()
                    }, initialTitle = getString(R.string.type_novel)
                ), PagedFragmentItem(
                    builder = {
                        SearchUserFragment()
                    }, initialTitle = getString(R.string.type_user)
                )
            ), this
        )
        binding.circleViewPager.adapter = adapter
        if (args.landingIndex > 0) {
            runOnceWithinFragmentLifecycle("setLandingIndex") {
                binding.circleViewPager.setCurrentItem(args.landingIndex, false)
            }
        }


    }
}