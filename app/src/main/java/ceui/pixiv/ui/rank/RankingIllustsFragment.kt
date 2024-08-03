package ceui.pixiv.ui.rank

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.pixiv.PixivFragment
import ceui.pixiv.pixivListViewModel
import ceui.pixiv.setUpStaggerLayout
import ceui.pixiv.ui.IllustCardHolder
import ceui.refactor.viewBinding

class RankingIllustsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel(
        loader = { Client.appApi.getRankingIllusts("day") },
        mapper = { illust -> listOf(IllustCardHolder(illust)) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
    }
}