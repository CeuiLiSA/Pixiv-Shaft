package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.RefreshHint
import ceui.loxia.observeEvent
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.refactor.viewBinding

data class SearchConfig(
    val keyword: String,
    val sort: String = "date_desc",
    val search_target: String = "partial_match_for_tags",
    val merge_plain_keyword_results: Boolean = true,
    val include_translated_tag_results: Boolean = true,
)

class SearchIllustMangaDataSource(
    private val provider: () -> SearchConfig
) : DataSource<Illust, IllustResponse>(
    dataFetcher = {
        val config = provider()
        Client.appApi.popularPreview(
            word = config.keyword,
            sort = config.sort,
            search_target = config.search_target,
            merge_plain_keyword_results = config.merge_plain_keyword_results,
            include_translated_tag_results = config.include_translated_tag_results,
        )
    },
    itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
) {
    override fun initialLoad(): Boolean {
        return provider().keyword.isNotEmpty()
    }
}

class SearchIlllustMangaFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel {
        SearchIllustMangaDataSource {
            SearchConfig(searchViewModel.keywords.value ?: "")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
        searchViewModel.searchIllustMangaEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh(RefreshHint.pullToRefresh())
        }
    }
}