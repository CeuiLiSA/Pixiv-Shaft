package ceui.pixiv.ui.chats

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMyChatsBinding
import ceui.loxia.ObjectType
import ceui.pixiv.ui.article.ArticlesFragment
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.home.WalkthroughFragment
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.viewBinding

class MyChatsFragment : TitledViewPagerFragment(R.layout.fragment_my_chats), HomeTabContainer {

    private val binding by viewBinding(FragmentMyChatsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        SquareFragment().apply {
                            arguments = SquareFragmentArgs(ObjectType.ILLUST).toBundle()
                        }
                    },
                    titleLiveData = getTitleLiveData(0).apply {
                        value = "插画"
                    }
                ),
                PagedFragmentItem(
                    builder = {
                        SquareFragment().apply {
                            arguments = SquareFragmentArgs(ObjectType.MANGA).toBundle()
                        }
                    },
                    titleLiveData = getTitleLiveData(1).apply {
                        value = "漫画"
                    }
                ),
                PagedFragmentItem(
                    builder = {
                        ArticlesFragment()
                    },
                    titleLiveData = getTitleLiveData(2).apply {
                        value = "Pixivision"
                    }
                ),
                PagedFragmentItem(
                    builder = {
                        WalkthroughFragment()
                    },
                    titleLiveData = getTitleLiveData(3).apply {
                        value = "画廊"
                    }
                ),
            ),
            this
        )
        binding.chatsViewpager.adapter = adapter
        binding.tabLayoutList.setUpWith(
            binding.chatsViewpager,
            binding.slidingCursor,
            viewLifecycleOwner,
            {})
    }
}