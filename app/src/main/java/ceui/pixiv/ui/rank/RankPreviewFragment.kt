package ceui.pixiv.ui.rank

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRankPreviewBinding
import ceui.loxia.Client
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivKeyedValueViewModel
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import kotlin.getValue

class RankPreviewFragment : PixivFragment(R.layout.fragment_rank_preview) {

    private val binding by viewBinding(FragmentRankPreviewBinding::bind)
    private val rankIllustViewModel by pixivKeyedValueViewModel(
        keyPrefix = "rank-illust-day",
        responseStore = createResponseStore({ "rank-illust-day" })
    ) {
        Client.appApi.getRankingIllusts("day")
    }
    private val rankMangaViewModel by pixivKeyedValueViewModel(
        keyPrefix = "rank-manga-day",
        responseStore = createResponseStore({ "rank-manga-day" })
    ) {
        Client.appApi.getRankingIllusts("day_manga")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.illustItems.setOnClick {
            pushFragment(R.id.navigation_rank)
        }
        val rankingAdapter = CommonAdapter(viewLifecycleOwner)
        binding.rankIllustList.adapter = rankingAdapter
        binding.rankIllustList.layoutManager = LinearLayoutManager(requireContext(),
            LinearLayoutManager.HORIZONTAL, false)
        rankIllustViewModel.result.observe(viewLifecycleOwner) { resp ->
            rankingAdapter.submitList(resp.displayList.map { RankPreviewHolder(it) })
        }


        binding.mangaItems.setOnClick {
            pushFragment(R.id.navigation_rank)
        }
        val rankingMangaAdapter = CommonAdapter(viewLifecycleOwner)
        binding.rankMangaList.adapter = rankingMangaAdapter
        binding.rankMangaList.layoutManager = LinearLayoutManager(requireContext(),
            LinearLayoutManager.HORIZONTAL, false)
        rankMangaViewModel.result.observe(viewLifecycleOwner) { resp ->
            rankingMangaAdapter.submitList(resp.displayList.map { RankPreviewHolder(it) })
        }
    }
}