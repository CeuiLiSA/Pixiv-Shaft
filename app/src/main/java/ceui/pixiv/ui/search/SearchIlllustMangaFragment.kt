package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.ObjectType
import ceui.loxia.RefreshHint
import ceui.loxia.observeEvent
import ceui.pixiv.ui.bottom.UsersYoriDialogFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.widgets.DialogViewModel
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding


class SearchIlllustMangaFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val dialogViewModel by activityViewModels<DialogViewModel>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel({ Pair(searchViewModel, dialogViewModel) }) { (vm, dialogVM) ->
        SearchIllustMangaDataSource {
            val count = dialogVM.chosenUsersYoriCount.value
            vm.buildSearchConfig(count, ObjectType.ILLUST)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        binding.radioTab.setTabs(listOf(
            "热度预览",
            "从新到旧",
            "从旧到新",
            "热度排序",
        ))
        binding.radioTab.setItemCickListener { index ->
            searchViewModel.illustSelectedRadioTabIndex.value = index
            val now = System.currentTimeMillis()
            searchViewModel.triggerSearchIllustMangaEvent(now)
        }
        searchViewModel.searchIllustMangaEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh(RefreshHint.InitialLoad)
        }
        searchViewModel.illustSelectedRadioTabIndex.observe(viewLifecycleOwner) { index ->
            binding.radioTab.selectTab(index)
            binding.usersYori.isVisible = (index == 1) || (index == 2)
        }
        dialogViewModel.chosenUsersYoriCount.observe(viewLifecycleOwner) { count ->
            binding.usersYori.text = "${count}users入り"
        }
        dialogViewModel.triggerUsersYoriEvent.observeEvent(this) { time ->
            searchViewModel.triggerSearchIllustMangaEvent(time)
        }
        binding.usersYori.setOnClick {
            UsersYoriDialogFragment().show(childFragmentManager, "UsersYoriDialogFragmentTag")
        }
    }
}