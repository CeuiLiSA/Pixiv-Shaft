package ceui.pixiv.ui.rank

import android.os.Bundle
import android.view.View
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.combineLatest
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivKeyedValueViewModel
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.discover.RankPreviewListHolder
import kotlin.getValue

class RankPreviewFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
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

        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL)
        combineLatest(rankIllustViewModel.result, rankMangaViewModel.result).observe(viewLifecycleOwner) { (illustRank, mangaRank) ->
            adapter.submitList(listOf(
                RankPreviewListHolder("Illust Ranking", illustRank?.displayList.orEmpty()),
                RankPreviewListHolder("Manga Ranking", mangaRank?.displayList.orEmpty()),
            ))
        }
    }
}