package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.widgets.DialogViewModel
import ceui.refactor.ppppx
import ceui.refactor.viewBinding

class SearchNovelFragment : PixivFragment(R.layout.fragment_pixiv_list) {
    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val dialogViewModel by activityViewModels<DialogViewModel>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel({ Pair(searchViewModel, dialogViewModel) }) { (vm, dialogVM) ->
        SearchNovelDataSource {
            val count = dialogVM.chosenUsersYoriCount.value
            vm.buildSearchConfig(count)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        binding.listView.addItemDecoration(LinearItemDecoration(20.ppppx))
    }
}