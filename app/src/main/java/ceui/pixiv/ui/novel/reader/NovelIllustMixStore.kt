package ceui.pixiv.ui.novel.reader

import ceui.lisa.activities.Shaft
import ceui.loxia.Illust
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
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
 * 所有来源都过 [IllustFeedItem] 的统一内容过滤链（屏蔽画师/标签、全局 R18、屏蔽 AI），
 * 与全仓其它列表同口径——过滤内含同步 Room 查询，所以取数固定切 [Dispatchers.IO]。
 * 拉取失败向上抛（Followed / Related 网络错）或返回空（Discover 池空、Related 无
 * tag 可搜），调用方回退纯文字阅读。
 */
object NovelIllustMixStore {

    private const val TTL_MS = 10 * 60 * 1000L
    private const val FETCH_LIMIT = 40
    private const val RELATED_TAG_LIMIT = 3

    /** Related 按小说逐篇取材；共享池来源 novelId 固定 0，同源多篇小说照旧共享一批候选。 */
    private data class CacheKey(val source: NovelIllustSource, val novelId: Long)

    private data class Entry(val illusts: List<Illust>, val fetchedAt: Long)

    private val mutex = Mutex()
    private val cache = mutableMapOf<CacheKey, Entry>()

    suspend fun get(source: NovelIllustSource, novel: Novel? = null): List<Illust> {
        if (source == NovelIllustSource.None) return emptyList()
        val key = CacheKey(source, if (source == NovelIllustSource.Related) novel?.id ?: 0L else 0L)
        mutex.withLock {
            val now = System.currentTimeMillis()
            cache[key]?.takeIf { now - it.fetchedAt < TTL_MS }?.let { return it.illusts }
            val fetched = withContext(Dispatchers.IO) {
                when (source) {
                    NovelIllustSource.Followed ->
                        Client.appApi.getFollowingIllusts(Params.TYPE_ALL).illusts
                            .mapNotNull { IllustFeedItem.of(it)?.illust }

                    NovelIllustSource.Discover ->
                        // getDiscoveryFeedDiversified 只进内存 recent 名单、不 markShown，
                        // 不会把发现页的候选池提前消耗掉（池回收是 issue #937 的事）。
                        DiscoveryPool.getDiscoveryFeedDiversified(FETCH_LIMIT)
                            .mapNotNull { entity ->
                                runCatching {
                                    Shaft.sGson.fromJson(entity.illustJson, Illust::class.java)
                                }.getOrNull()
                            }
                            .mapNotNull { IllustFeedItem.of(it)?.illust }

                    NovelIllustSource.Related -> fetchRelated(novel)

                    NovelIllustSource.None -> emptyList()
                }
            }.filter { it.id > 0 && pickUrl(it) != null }
            if (fetched.isNotEmpty()) {
                cache[key] = Entry(fetched, now)
            }
            return fetched
        }
    }

    /**
     * 「与本作相关」：取小说前 [RELATED_TAG_LIMIT] 个实义 tag 精确搜索插画
     * （R-18 系 meta tag 搜出来只有涩图没有相关性，跳过），逐 tag 凑满
     * [FETCH_LIMIT] 即止。sort 固定 date_desc——popular 系 sort 是会员专属
     * （见 SearchIllustRepo 的 sort 路由），不值得为取材再兜一层会员分支。
     * 关注画师优先是 [IllustMixRanker] 的事，pixiv 没有「关注 ∩ tag」服务端过滤。
     */
    private suspend fun fetchRelated(novel: Novel?): List<Illust> {
        val words = novel?.tags.orEmpty()
            .mapNotNull { it.name?.trim() }
            .filter { it.isNotEmpty() && it !in RELATED_META_TAGS }
            .take(RELATED_TAG_LIMIT)
        if (words.isEmpty()) return emptyList()
        val result = LinkedHashMap<Long, Illust>()
        for (word in words) {
            if (result.size >= FETCH_LIMIT) break
            Client.appApi.searchIllustManga(
                word = word,
                sort = "date_desc",
                search_target = "exact_match_for_tags",
                merge_plain_keyword_results = true,
                include_translated_tag_results = true,
            ).illusts
                .mapNotNull { IllustFeedItem.of(it)?.illust }
                .forEach { result.putIfAbsent(it.id, it) }
        }
        return result.values.toList()
    }

    private val RELATED_META_TAGS = setOf("R-18", "R-18G", "R18", "R18G")

    /** 混排图独占展示（横向整页 / 纵向通栏），large 优先于内嵌插图惯用的 medium。 */
    fun pickUrl(illust: Illust): String? = illust.image_urls?.let {
        it.large ?: it.medium ?: it.square_medium ?: it.original
    }
}
