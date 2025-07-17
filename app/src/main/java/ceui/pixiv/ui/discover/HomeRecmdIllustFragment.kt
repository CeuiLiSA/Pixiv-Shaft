package ceui.pixiv.ui.discover

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.threadSafeArgs
import ceui.pixiv.paging.HomeRecommendIllustRepository
import ceui.pixiv.paging.PagingViewModel
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class HomeRecmdIllustFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by threadSafeArgs<HomeRecmdIllustFragmentArgs>()
    private val viewModel by constructVM({ AppDatabase.getAppDatabase(requireContext()) to safeArgs }) { (database, args) ->
        PagingViewModel(
            database,
            HomeRecommendIllustRepository(args.objectType)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, { entity ->
            listOf(IllustCardHolder(entity.typedObject()))
        }, ListMode.STAGGERED_GRID)
    }
}
