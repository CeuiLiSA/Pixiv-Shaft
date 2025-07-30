package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class WalkthroughFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val viewModel by pagingViewModel {
        object : PagingAPIRepository<Illust>() {
            override suspend fun loadFirst(): KListShow<Illust> {
                return Client.appApi.getWalkthroughWorks()
            }

            override fun mapper(entity: Illust): List<ListItemHolder> {
                return listOf(IllustCardHolder(entity))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel)
    }
}
