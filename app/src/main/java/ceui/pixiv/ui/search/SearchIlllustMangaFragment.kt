package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.RefreshHint
import ceui.loxia.observeEvent
import ceui.pixiv.ui.bottom.ItemListDialogFragment
import ceui.pixiv.ui.bottom.UsersYoriDialogFragment
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.widgets.DialogViewModel
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding

data class SearchConfig(
    val keyword: String,
    val sort: String = "date_desc",
    val usersYori: String = "",
    val search_target: String = "partial_match_for_tags",
    val merge_plain_keyword_results: Boolean = true,
    val include_translated_tag_results: Boolean = true,
)

class SearchIllustMangaDataSource(
    private val provider: () -> SearchConfig
) : DataSource<Illust, IllustResponse>(
    dataFetcher = {
        val config = provider()
        if (config.sort == SortType.POPULAR_PREVIEW) {
            Client.appApi.popularPreview(
                word = config.keyword,
                sort = config.sort,
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
            )
        } else {
            val word = if (config.usersYori.isNotEmpty()) {
                config.keyword + " " + config.usersYori
            } else {
                config.keyword
            }
            Client.appApi.searchIllustManga(
                word = word,
                sort = config.sort,
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
            )
        }
    },
    itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
) {
    override fun initialLoad(): Boolean {
        return provider().keyword.isNotEmpty()
    }
}

class SearchIlllustMangaFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val dialogViewModel by activityViewModels<DialogViewModel>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel({ Pair(searchViewModel, dialogViewModel) }) { (vm, dialogVM) ->
        SearchIllustMangaDataSource {
            val tabIndex = vm.selectedRadioTabIndex.value ?: 0
            val sort = when (tabIndex) {
                0 -> {
                    SortType.POPULAR_PREVIEW
                }
                1 -> {
                    SortType.DATE_DESC
                }
                2 -> {
                    SortType.DATE_ASC
                }
                else -> {
                    SortType.POPULAR_DESC
                }
            }
            val usersYori = dialogVM.chosenUsersYoriCount.value ?: 0
            val yoriString = if (usersYori > 0) {
                "${usersYori}users入り"
            } else {
                ""
            }
            SearchConfig(
                keyword = vm.tagList.value?.map { it.name }?.joinToString(separator = " ") ?: "",
                usersYori = yoriString,
                sort = sort,
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
        binding.radioTab.setTabs(listOf(
            "热度预览",
            "从新到旧",
            "从旧到新",
            "热度排序",
        ))
        binding.radioTab.setItemCickListener { index ->
            searchViewModel.selectedRadioTabIndex.value = index
            val now = System.currentTimeMillis()
            searchViewModel.triggerSearchIllustMangaEvent(now)
        }
        searchViewModel.searchIllustMangaEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh(RefreshHint.InitialLoad)
        }
        searchViewModel.selectedRadioTabIndex.observe(viewLifecycleOwner) { index ->
            binding.radioTab.selectTab(index)
            binding.usersYori.isVisible = index == 1
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