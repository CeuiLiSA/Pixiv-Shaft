package ceui.pixiv.ui.trending

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.search.SearchViewPagerFragment
import ceui.pixiv.ui.search.SearchViewPagerFragmentArgs
import ceui.refactor.viewBinding

class TrendingTagsFragment : PixivFragment(R.layout.fragment_pixiv_list), TrendingTagActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<TrendingTagsFragmentArgs>()
    private val viewModel by pixivListViewModel {
        DataSource(
            dataFetcher = { Client.appApi.trendingTags(args.objectType) },
            itemMapper = { trendingTag -> listOf(TrendingTagHolder(trendingTag)) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        binding.listView.layoutManager = GridLayoutManager(requireContext(), 3)
    }

    override fun onClickTrendingTag(trendingTag: TrendingTag) {
        pushFragment(R.id.navigation_search_viewpager, SearchViewPagerFragmentArgs(
            keyword = trendingTag.tag ?: "",
        ).toBundle())
    }

    override fun onLongClickTrendingTag(trendingTag: TrendingTag) {
        trendingTag.illust?.let {
            ObjectPool.update(it)
            onClickIllustCard(it)
        }
    }
}