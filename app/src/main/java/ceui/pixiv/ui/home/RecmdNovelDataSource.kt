package ceui.pixiv.ui.home

import ceui.loxia.Client
import ceui.loxia.HomeIllustResponse
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.createResponseStore

class RecmdNovelDataSource(
) : DataSource<Novel, NovelResponse>(
    dataFetcher = { hint ->
        Client.appApi.getRecmdNovels()
    },
    responseStore = createResponseStore({ "home-recommend-novel-api" }),
    itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
    filter = { novel -> novel.visible != false }
)