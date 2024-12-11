package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.RefreshHint
import ceui.loxia.UserPreview
import ceui.loxia.UserPreviewResponse
import ceui.loxia.observeEvent
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.user.UserPreviewHolder
import ceui.refactor.ppppx
import ceui.refactor.viewBinding

class SearchUserSource(
    private val keywordProvider: () -> String
) : DataSource<UserPreview, UserPreviewResponse>(
    dataFetcher = {
        val keyword = keywordProvider()
        Client.appApi.searchUser(keyword)
    },
    itemMapper = { preview -> listOf(UserPreviewHolder(preview)) }
) {
    override fun initialLoad(): Boolean {
        return keywordProvider().isNotEmpty()
    }
}

class SearchUserFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val viewModel by pixivListViewModel {
        SearchUserSource {
            searchViewModel.tagList.value?.map { it.name }?.joinToString(separator = " ") ?: ""
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        searchViewModel.searchUserEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh(RefreshHint.InitialLoad)
        }
    }
}