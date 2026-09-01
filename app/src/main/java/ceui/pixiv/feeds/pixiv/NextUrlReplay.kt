package ceui.pixiv.feeds.pixiv

import ceui.pixiv.api.Client
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * pixiv nextUrl 翻页协议的唯一实现：GET 原始 JSON，按给定类型反序列化。
 *
 * 曾与 legacy `DataSource.loadMoreImpl` 共用，故住在 `ui.common`；legacy 框架删除后本函数
 * 只服务 feeds，遂随 [PixivFeedSource] 迁入本子包——`ceui.pixiv.feeds` 核心从此不再反向
 * 依赖 `ceui.pixiv.ui.*`（依赖方向恒为 ui → feeds）。
 *
 * 协议修复只改这一处。
 */
suspend fun <T> replayNextUrl(gson: Gson, nextUrl: String, responseClass: Class<T>): T {
    return withContext(Dispatchers.IO) {
        val responseJson = Client.appApi.generalGet(nextUrl).string()
        gson.fromJson(responseJson, responseClass)
    }
}
