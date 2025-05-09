package ceui.pixiv.ui.rank

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.RefreshHint
import ceui.loxia.observeEvent
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel

class RankingIllustsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<RankingIllustsFragmentArgs>()
    private val rankDayViewModal by viewModels<RankDayViewModel>(ownerProducer = { requireParentFragment() })
    private val viewModel by pixivListViewModel({ safeArgs.mode to rankDayViewModal.rankDay }) { (mode, rankDay) ->
        DataSource(
            dataFetcher = { Client.appApi.getRankingIllusts(mode, rankDay.value) },
            responseStore = createResponseStore({ "rank-illust-$mode" }),
            itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        rankDayViewModal.refreshEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh(RefreshHint.FetchingLatest)
        }
    }
}