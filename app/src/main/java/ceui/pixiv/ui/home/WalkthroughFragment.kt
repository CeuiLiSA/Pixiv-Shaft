package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.HomeIllustResponse
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.refactor.viewBinding

class WalkthroughFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel { WalkthroughDataSource() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
    }
}

class WalkthroughDataSource(
    private val responseStore: ResponseStore<IllustResponse> = createResponseStore(
        keyProvider = { "home-walkthrough-api" },
        dataLoader = { Client.appApi.getWalkthroughWorks() }
    )
) : DataSource<Illust, IllustResponse>(
    dataFetcher = { hint -> responseStore.retrieveData(hint) },
    itemMapper = { illust -> listOf(IllustCardHolder(illust)) },
)