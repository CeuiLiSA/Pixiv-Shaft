package ceui.pixiv.ui.discovery

import ceui.pixiv.api.PixivWebApi
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.ui.common.IllustFeedItem
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WebDiscoveryMode(val apiValue: String) {
    ALL("all"), SAFE("safe"), R18("r18");

    fun accepts(xRestrict: Int): Boolean = when (this) {
        ALL -> true
        SAFE -> xRestrict == 0
        R18 -> xRestrict > 0
    }

    companion object {
        fun initial(filterR18: Boolean): WebDiscoveryMode = if (filterR18) SAFE else ALL
    }
}

/** 独立官网推荐源，不读写 DiscoveryPool，也不转用 app-api 的首页推荐。 */
class WebDiscoverySource(
    private val api: PixivWebApi,
    private val mode: WebDiscoveryMode,
    private val hasWebSession: () -> Boolean = { WebDiscoverySession.isCurrentAccount },
    // 当前页的三态筛选优先于全局 R18 过滤；屏蔽作者、标签和 AI 过滤照常生效。
    private val toItem: (Illust) -> FeedItem? = { IllustFeedItem.of(it, skipR18Filter = true) },
) : FeedSource<String> {
    override suspend fun load(cursor: String?): FeedPage<String> {
        // 无同账号网页会话时显示登录动作，避免推荐和收藏状态串号。
        if (!hasWebSession()) return FeedPage(emptyList(), null)
        val response = api.getDiscoveryArtworks(mode.apiValue)
        if (response.error == true) {
            throw IOException(response.message?.takeIf { it.isNotBlank() } ?: "Discovery request failed")
        }
        val works = response.body?.thumbnails?.illust
            ?: throw IOException("Missing discovery artworks")
        val items = withContext(Dispatchers.Default) {
            works.asSequence()
                .filter { it.id > 0 && it.userId > 0 && !it.isMasked && !it.url.isNullOrBlank() }
                .filter { mode.accepts(it.xRestrict) }
                .mapNotNull { toItem(it.toIllust()) }
                .toList()
        }
        // 仅用本地序号表示还可继续请求；绝不把它当作网页 page/offset 或 app-api nextUrl。
        // 服务端返回短页仍可继续；过滤空页、跨批去重和请求预算由 FeedViewModel 承担。
        return FeedPage(items, if (works.isEmpty()) null else ((cursor?.toIntOrNull() ?: 0) + 1).toString())
    }
}
