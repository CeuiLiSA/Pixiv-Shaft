package ceui.pixiv.ui.discover

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.threadSafeArgs
import ceui.pixiv.paging.HomeRecommendIllustRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class HomeRecmdIllustFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by threadSafeArgs<HomeRecmdIllustFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs.objectType }) { objectType ->
        HomeRecommendIllustRepository(objectType)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.STAGGERED_GRID)
    }
}
