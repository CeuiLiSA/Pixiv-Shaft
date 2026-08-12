package ceui.pixiv.ui.novel.reader

import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.novel.reader.model.NovelIllustSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 「自动混排插画」的取材池（issue #999），进程级缓存：同一来源 [TTL_MS] 内多篇小说
 * 共享一批候选，不每篇重拉（参考 [NovelTextCache] 的定位）。
 *
 * 两个来源都过 [IllustFeedItem] 的统一内容过滤链（屏蔽画师/标签、全局 R18、屏蔽 AI），
 * 与全仓其它列表同口径——过滤内含同步 Room 查询，所以取数固定切 [Dispatchers.IO]。
 * 拉取失败向上抛（Followed 网络错）或返回空（Discover 池空），调用方回退纯文字阅读。
 */
object NovelIllustMixStore {

    private const val TTL_MS = 10 * 60 * 1000L
    private const val FETCH_LIMIT = 40

    private data class Entry(val illusts: List<Illust>, val fetchedAt: Long)

    private val mutex = Mutex()
    private val cache = mutableMapOf<NovelIllustSource, Entry>()

    suspend fun get(source: NovelIllustSource): List<Illust> {
        if (source == NovelIllustSource.None) return emptyList()
        mutex.withLock {
            val now = System.currentTimeMillis()
            cache[source]?.takeIf { now - it.fetchedAt < TTL_MS }?.let { return it.illusts }
            val fetched = withContext(Dispatchers.IO) {
                when (source) {
                    NovelIllustSource.Followed ->
                        Client.appApi.getFollowingIllusts(Params.TYPE_ALL).illusts
                            .mapNotNull { IllustFeedItem.from(it)?.illust }

                    NovelIllustSource.Discover ->
                        // getDiscoveryFeedDiversified 只进内存 recent 名单、不 markShown，
                        // 不会把发现页的候选池提前消耗掉（池回收是 issue #937 的事）。
                        DiscoveryPool.getDiscoveryFeedDiversified(FETCH_LIMIT)
                            .mapNotNull { entity ->
                                runCatching {
                                    Shaft.sGson.fromJson(entity.illustJson, IllustsBean::class.java)
                                }.getOrNull()
                            }
                            .mapNotNull { IllustFeedItem.fromBean(it)?.illust }

                    NovelIllustSource.None -> emptyList()
                }
            }.filter { it.id > 0 && pickUrl(it) != null }
            if (fetched.isNotEmpty()) {
                cache[source] = Entry(fetched, now)
            }
            return fetched
        }
    }

    /** 混排图独占展示（横向整页 / 纵向通栏），large 优先于内嵌插图惯用的 medium。 */
    fun pickUrl(illust: Illust): String? = illust.image_urls?.let {
        it.large ?: it.medium ?: it.square_medium ?: it.original
    }
}
