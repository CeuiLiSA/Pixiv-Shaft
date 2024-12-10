package ceui.pixiv.ui.rank

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.IllustResponse
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.createResponseStore
import ceui.refactor.viewBinding

class RankingIllustsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<RankingIllustsFragmentArgs>()
    private val viewModel by pixivListViewModel({ safeArgs.mode }) { mode ->
        val responseStore = createResponseStore(
            keyProvider = { "rank-illust-$mode" },
            dataLoader = { Client.appApi.getRankingIllusts(mode) }
        )
        DataSource(
            dataFetcher = { responseStore.retrieveData() },
            itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
    }
}