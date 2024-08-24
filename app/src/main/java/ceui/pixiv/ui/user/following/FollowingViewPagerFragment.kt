package ceui.pixiv.ui.user.following

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMyCirclesBinding
import ceui.lisa.utils.Params
import ceui.loxia.ObjectType
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.trending.TrendingTagsFragment
import ceui.pixiv.ui.trending.TrendingTagsFragmentArgs
import ceui.pixiv.ui.user.recommend.RecommendUsersFragment
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.viewBinding

class FollowingViewPagerFragment : PixivFragment(R.layout.fragment_my_circles), HomeTabContainer {

    private val binding by viewBinding(FragmentMyCirclesBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        FollowingPostFragment().apply {
                            arguments = FollowingPostFragmentArgs(
                                ObjectType.ILLUST,
                                Params.TYPE_PUBLIC
                            ).toBundle()
                        }
                    },
                    title = getString(R.string.string_391)
                ),
                PagedFragmentItem(
                    builder = {
                        FollowingPostFragment().apply {
                            arguments = FollowingPostFragmentArgs(
                                ObjectType.ILLUST,
                                Params.TYPE_PRIVATE
                            ).toBundle()
                        }
                    },
                    title = getString(R.string.string_392)
                ),
            ),
            this
        )
        binding.circlesViewpager.adapter = adapter
        binding.tabLayoutList.setUpWith(
            binding.circlesViewpager,
            binding.slidingCursor,
            viewLifecycleOwner,
            {})
    }
}