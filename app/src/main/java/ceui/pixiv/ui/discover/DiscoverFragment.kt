package ceui.pixiv.ui.discover

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentDiscoverBinding
import ceui.loxia.ObjectType
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.home.RecmdNovelFragment
import ceui.pixiv.widgets.setUpWith
import ceui.pixiv.widgets.setupVerticalAwareViewPager2

class DiscoverFragment : TitledViewPagerFragment(R.layout.fragment_discover), HomeTabContainer {

    private val binding by viewBinding(FragmentDiscoverBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupVerticalAwareViewPager2(binding.discoverViewPager)
        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        HomeRecmdIllustFragment().apply {
                            arguments = HomeRecmdIllustFragmentArgs(ObjectType.ILLUST).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_illust)
                ),
                PagedFragmentItem(
                    builder = {
                        HomeRecmdIllustFragment().apply {
                            arguments = HomeRecmdIllustFragmentArgs(ObjectType.MANGA).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_manga)
                ),
                PagedFragmentItem(
                    builder = {
                        RecmdNovelFragment()
                    },
                    initialTitle = getString(R.string.type_novel)
                ),
                PagedFragmentItem(
                    builder = {
                        DiscoverAllFragment()
                    },
                    initialTitle = getString(R.string.string_207)
                ),
            ),
            this
        )
        binding.discoverViewPager.adapter = adapter
        binding.tabLayoutList.setUpWith(
            binding.discoverViewPager,
            binding.slidingCursor,
            viewLifecycleOwner,
            {})
    }
}