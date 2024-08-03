package ceui.pixiv.ui.home

import ceui.loxia.Client
import ceui.loxia.HomeIllustResponse
import ceui.loxia.Illust
import ceui.loxia.RefreshHint
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import com.tencent.mmkv.MMKV

class HomeDataSource : DataSource<Illust, HomeIllustResponse>(
    loader = { Client.appApi.getHomeData() },
    mapper = { illust -> listOf(IllustCardHolder(illust)) }
) {

    private val prefStore: MMKV by lazy {
        MMKV.mmkvWithID("api-cache")
    }

    override suspend fun prepareRefreshResponse(): HomeIllustResponse {
        val key = "home-data"
        val json = prefStore.getString(key, "")
        return if (json?.isNotEmpty() == true) {
            gson.fromJson(json, HomeIllustResponse::class.java)
        } else {
            val response = Client.appApi.getHomeData()
            prefStore.putString(key, gson.toJson(response))
            response
        }
    }
}