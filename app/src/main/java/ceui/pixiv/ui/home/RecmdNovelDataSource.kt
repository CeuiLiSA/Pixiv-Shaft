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

class RecmdNovelDataSource(
    private val responseStore: ResponseStore<NovelResponse> = ResponseStore(
        { "home-recommend-novel-api" },
        1800 * 1000L,
        NovelResponse::class.java,
        { Client.appApi.getRecmdNovels() }
    )
) : DataSource<Novel, NovelResponse>(
    dataFetcher = {
        val resp = responseStore.retrieveData()
        resp.copy(novels = resp.novels.sortedBy { it.text_length })
    },
    itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
)