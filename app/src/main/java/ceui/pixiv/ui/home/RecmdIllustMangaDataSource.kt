package ceui.pixiv.ui.home

import ceui.loxia.Client
import ceui.loxia.HomeIllustResponse
import ceui.loxia.Illust
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ResponseStore
import timber.log.Timber

class RecmdIllustMangaDataSource(
    private val args: RecmdIllustMangaFragmentArgs,
    private val responseStore: ResponseStore<HomeIllustResponse> = ResponseStore(
        { "home-recommend-${args.objectType}-api" },
        1800 * 1000L,
        HomeIllustResponse::class.java,
        { Client.appApi.getHomeData(args.objectType) }
    )
) : DataSource<Illust, HomeIllustResponse>(
    dataFetcher = { hint -> responseStore.retrieveData(hint) },
    itemMapper = { illust -> listOf(IllustCardHolder(illust)) },
)