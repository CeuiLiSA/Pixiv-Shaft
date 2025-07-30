package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.UserPreview
import ceui.loxia.observeEvent
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.user.UserPreviewHolder

class SearchUserSource(
    private val keywordProvider: () -> String
) : PagingAPIRepository<UserPreview>() {
//    override fun initialLoad(): Boolean {
//        return keywordProvider().isNotEmpty()
//    }

    override suspend fun loadFirst(): KListShow<UserPreview> {
        val keyword = keywordProvider()
        return Client.appApi.searchUser(keyword)
    }

    override fun mapper(entity: UserPreview): List<ListItemHolder> {
        return listOf(UserPreviewHolder(entity))
    }
}

class SearchUserFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val viewModel by pagingViewModel({ searchViewModel }) { vm ->
        SearchUserSource(
            keywordProvider = {
                vm.tagList.value?.map { it.name }?.joinToString(separator = " ") ?: ""
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
        searchViewModel.searchUserEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh()
        }
    }
}