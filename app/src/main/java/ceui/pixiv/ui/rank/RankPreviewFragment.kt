package ceui.pixiv.ui.rank

import android.os.Bundle
import android.view.View
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.IllustResponse
import ceui.loxia.ObjectType
import ceui.loxia.combineLatest
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivKeyedValueViewModel
import ceui.pixiv.ui.common.repo.ResponseStoreRepository
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.discover.RankPreviewListHolder
import kotlin.getValue

class RankPreviewRepository(
    private val mode: String,
    responseStore: ResponseStore<IllustResponse>,
) : ResponseStoreRepository<IllustResponse>(responseStore) {
    override suspend fun fetchRemoteDataImpl(): IllustResponse {
        return Client.appApi.getRankingIllusts(mode)
    }
}

class RankPreviewFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val rankIllustViewModel by pixivKeyedValueViewModel(
        keyPrefix = "rank-illust-day",
        repositoryProducer = {
            RankPreviewRepository(
                "day", createResponseStore({ "rank-illust-day" })
            )
        })
    private val rankMangaViewModel by pixivKeyedValueViewModel(
        keyPrefix = "rank-manga-day",
        repositoryProducer = {
            RankPreviewRepository("day_manga", createResponseStore({ "rank-manga-day" }))
        })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL_NO_MARGIN)
        combineLatest(rankIllustViewModel.result, rankMangaViewModel.result).observe(
            viewLifecycleOwner
        ) { (illustLoadResult, mangaLoadResult) ->
            val illustRank = illustLoadResult?.data ?: return@observe
            val mangaRank = mangaLoadResult?.data ?: return@observe
            adapter.submitList(
                listOf(
                    RankPreviewListHolder(
                        "Illust Ranking", illustRank.displayList, showExtraPadding = true
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_rank, RankFragmentArgs(ObjectType.ILLUST).toBundle()
                        )
                    },
                    RankPreviewListHolder(
                        "Manga Ranking", mangaRank.displayList, showExtraPadding = true
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_rank, RankFragmentArgs(ObjectType.MANGA).toBundle()
                        )
                    },
                )
            )
        }
    }
}