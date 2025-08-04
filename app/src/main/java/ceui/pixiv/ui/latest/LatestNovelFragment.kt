package ceui.pixiv.ui.latest

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.pixiv.paging.PagingNovelAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class LatestNovelFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val viewModel by pagingViewModel {
        PagingNovelAPIRepository {
            Client.appApi.getLatestNovels()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
    }
}