package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.loxia.observeEvent
import ceui.pixiv.paging.PagingUserAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding


class SearchUserFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val viewModel by pagingViewModel({ searchViewModel }) { vm ->
        PagingUserAPIRepository {
            val keyword = vm.tagList.value?.map { it.name }?.joinToString(separator = " ") ?: ""
            Client.appApi.searchUser(keyword)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
        searchViewModel.searchUserEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh()
        }
    }
}