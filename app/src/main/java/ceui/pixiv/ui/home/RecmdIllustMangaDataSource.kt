package ceui.pixiv.ui.home

import ceui.loxia.Client
import ceui.loxia.HomeIllustResponse
import ceui.loxia.Illust
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.createResponseStore
import timber.log.Timber

class RecmdIllustMangaDataSource(
    private val args: RecmdIllustMangaFragmentArgs,
) : DataSource<Illust, HomeIllustResponse>(
    dataFetcher = { hint -> Client.appApi.getHomeData(args.objectType) },
    responseStore = createResponseStore({ "home-recommend-${args.objectType}-api" }),
    itemMapper = { illust -> listOf(IllustCardHolder(illust)) },
)