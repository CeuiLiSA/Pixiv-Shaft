package ceui.pixiv.ui.user.following

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMyCirclesBinding
import ceui.lisa.utils.Params
import ceui.loxia.ObjectType
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.CommonViewPagerViewModel
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.viewBinding

class FollowingViewPagerFragment : TitledViewPagerFragment(R.layout.fragment_my_circles), HomeTabContainer {

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
                    initialTitle = getString(R.string.string_391)
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
                    initialTitle = getString(R.string.string_392)
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