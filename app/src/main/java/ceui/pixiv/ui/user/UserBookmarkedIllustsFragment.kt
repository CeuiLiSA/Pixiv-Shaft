package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.task.FetchAllTask
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding

class UserBookmarkedIllustsFragment: PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel {
        DataSource(
            dataFetcher = { Client.appApi.getUserBookmarkedIllusts(args.userId) },
            itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
        )
    }
    private val args by navArgs<UserBookmarkedIllustsFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviMore.setOnClick {
            object : FetchAllTask<Illust, IllustResponse>(initialLoader = {
                Client.appApi.getUserBookmarkedIllusts(args.userId)
            }) {
                override fun onEnd(results: List<Illust>) {
                    super.onEnd(results)
                    Common.showLog("FetchAllTask out onEnd ${results.size}")
                }
            }
        }
        setUpStaggerLayout(binding, viewModel)
    }
}