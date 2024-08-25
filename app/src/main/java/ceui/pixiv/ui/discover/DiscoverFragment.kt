package ceui.pixiv.ui.discover

import android.os.Bundle
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentDiscoverBinding
import ceui.lisa.utils.Common
import ceui.loxia.ObjectType
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.home.HomeFragment
import ceui.pixiv.ui.home.HomeFragmentArgs
import ceui.pixiv.ui.trending.TrendingTagsFragment
import ceui.pixiv.ui.trending.TrendingTagsFragmentArgs
import ceui.pixiv.ui.user.recommend.RecommendUsersFragment
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.viewBinding
import com.google.android.material.appbar.AppBarLayout

class DiscoverFragment : PixivFragment(R.layout.fragment_discover), HomeTabContainer {

    private val binding by viewBinding(FragmentDiscoverBinding::bind)
    private val viewModel by viewModels<DiscoverViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        HomeFragment().apply {
                            arguments = HomeFragmentArgs(ObjectType.ILLUST).toBundle()
                        }
                    },
                    title = getString(R.string.type_illust)
                ),
                PagedFragmentItem(
                    builder = {
                        HomeFragment().apply {
                            arguments = HomeFragmentArgs(ObjectType.MANGA).toBundle()
                        }
                    },
                    title = getString(R.string.type_manga)
                ),
                PagedFragmentItem(
                    builder = {
                        HomeFragment().apply {
                            arguments = HomeFragmentArgs(ObjectType.MANGA).toBundle()
                        }
                    },
                    title = getString(R.string.type_novel)
                )
            ),
            this
        )
        binding.discoverViewPager.adapter = adapter
        binding.tabLayoutList.setUpWith(binding.discoverViewPager, binding.slidingCursor, viewLifecycleOwner, {})
    }
}