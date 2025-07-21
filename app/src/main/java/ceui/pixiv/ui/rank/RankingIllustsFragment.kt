package ceui.pixiv.ui.rank

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.observeEvent
import ceui.loxia.threadSafeArgs
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class RankingIllustsFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by threadSafeArgs<RankingIllustsFragmentArgs>()
    private val rankDayViewModal by viewModels<RankDayViewModel>(ownerProducer = { requireParentFragment() })
    private val viewModel by pagingViewModel({ safeArgs.mode to rankDayViewModal.rankDay }) { (mode, rankDay) ->
        RankingIllustRepository(mode, rankDay)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel)
        rankDayViewModal.refreshEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh()
        }
    }
}