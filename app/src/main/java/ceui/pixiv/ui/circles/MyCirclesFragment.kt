package ceui.pixiv.ui.circles

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMyCirclesBinding
import ceui.loxia.ObjectType
import ceui.pixiv.ui.common.CommonViewPagerViewModel
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.trending.TrendingTagsFragment
import ceui.pixiv.ui.trending.TrendingTagsFragmentArgs
import ceui.pixiv.ui.user.recommend.RecommendUsersFragment
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.viewBinding

class MyCirclesFragment : TitledViewPagerFragment(R.layout.fragment_my_circles), HomeTabContainer {

    private val binding by viewBinding(FragmentMyCirclesBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        TrendingTagsFragment().apply {
                            arguments = TrendingTagsFragmentArgs(ObjectType.ILLUST).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_136)
                ),
                PagedFragmentItem(
                    builder = {
                        TrendingTagsFragment().apply {
                            arguments = TrendingTagsFragmentArgs(ObjectType.NOVEL).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_novel)
                ),
                PagedFragmentItem(
                    builder = {
                        RecommendUsersFragment()
                    },
                    initialTitle = getString(R.string.recommend_user)
                )
            ),
            this
        )
        binding.circlesViewpager.adapter = adapter
        binding.tabLayoutList.setUpWith(binding.circlesViewpager, binding.slidingCursor, viewLifecycleOwner, {})
    }
}