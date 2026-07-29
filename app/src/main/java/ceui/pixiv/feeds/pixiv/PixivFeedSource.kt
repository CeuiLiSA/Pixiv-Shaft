package ceui.pixiv.feeds.pixiv

import ceui.loxia.KListShow
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.cache.CachedFirstPage
import ceui.pixiv.feeds.cache.DEFAULT_FEED_CACHE_MAX_AGE
import ceui.pixiv.feeds.cache.FeedFirstPageCache
import ceui.pixiv.feeds.cache.feedCacheWriteScope
import ceui.pixiv.feeds.cache.feedFirstPageCache
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration

/**
 * 翻页反序列化用的共享 Gson（沿用 legacy DataSource 的 vanilla Gson）；缓存快照另走 Shaft.sGson
 * （见 feedFirstPageCache）。两者都是无自定义适配器的普通 Gson，行为一致，各自独立。
 *
 * 进程级单例而不是每个 [PixivFeedSource] 各持一个：Gson 线程安全、可复用，且内部按类型缓存
 * TypeAdapter；全 app 同时存活上百个 feed 源，人手一个只是白白重复反射建缓存、多占内存。
 */
private val pagingGson = Gson()

/**
 * pixiv nextUrl 翻页协议到 [FeedSource] 的桥接。
 *
 * 住在 `feeds.pixiv` 子包而不是 `feeds` 核心：核心（[FeedViewModel] / [FeedFragment] / [FeedSource]）
 * 对 pixiv 一无所知，所有协议知识收在本子包，依赖方向恒为 `ui → feeds.pixiv → feeds`。
 *
 * - 第一页走类型安全的 Retrofit suspend 接口；后续页复用 [replayNextUrl]；
 * - [mapper] 拿到整个响应做条目映射（在 Default 线程执行，禁止碰 View），并按 [FeedLoadPhase]
 *   区分首屏 / 翻页 / 缓存恢复——插 section 头、按响应字段分组、逐条转换/过滤都表达得出来；
 * - [mapper] 允许携带每页副作用（喂 DiscoveryPool、写推荐浏览历史等），但必须容忍
 *   重复执行：刷新会对第一页重放，空页追载会连续调用——副作用要幂等或重放无害；
 *   **缓存恢复（[FeedLoadPhase.CacheRestore]）时别做「拉取成功」副作用**（拿旧数据重放会污染下游）；
 * - [nextCursorOf] 是翻页门控钩子，默认取响应的 nextUrl。返回 null 即「到此为止」——
 *   「只出首页」的货架 / 被设置项门控的列表（如「相关作品无限下滑」关时）用它表达，
 *   不必为了改一个游标就手抄整套协议（那样会连带丢掉缓存、phase 区分与 main-safe 保证）；
 * - 传了 [cache] 即开「本地优先」：首屏网络成功后落盘，冷启时 [loadFromCache] 秒显旧首屏
 *   （见 [cachedPixivFeedSource]）；不传则退化为纯网络；
 * - 本类实例被 [FeedViewModel] 持有到页面最终销毁：[initialFetch] / [mapper] / [cache] 不要捕获
 *   Fragment / View / Context，需要的值先取成局部变量（零捕获约定见 feedViewModels 文档）。
 *
 * [responseClass] 是构造期就交进来的类型令牌（用 [pixivFeedSource] / [cachedPixivFeedSource]
 * 由 reified 类型参数自动填）。曾经它是个 `@Volatile var`，在首屏 `load(null)` 成功时才被
 * 运行时类型立起来——于是 `load(cursor != null)` 必须 `requireNotNull` 兜「首页尚未加载」这个
 * 本不该存在的运行时态，[loadFromCache] 也得专门补一句「先立起 responseClass」以防缓存展示
 * 期间用户翻页。类型在构造期完全可知，交进来即可让这类时序耦合在编译期消失；顺带修掉
 * 「缓存按静态类型 [Resp] 序列化、翻页却按运行时类型反序列化」的不一致。
 */
class PixivFeedSource<Resp : KListShow<*>>(
    private val responseClass: Class<Resp>,
    private val initialFetch: suspend () -> Resp,
    private val cache: FeedFirstPageCache<Resp>? = null,
    /** 首屏落盘的 scope（fire-and-forget，见 [feedCacheWriteScope]）。单测注入可控 scope 以便等待。 */
    private val cacheWriteScope: CoroutineScope = feedCacheWriteScope,
    /** 翻页门控：给出下一页游标，null = 到此为止。默认取响应自带的 nextUrl。 */
    private val nextCursorOf: (Resp) -> String? = { it.nextPageUrl?.takeIf(String::isNotEmpty) },
    /**
     * 冷启命中快照后还要不要网络刷新（见 [FeedSource.refreshAfterCacheHit]）。
     * 每次刷新现读，所以设置项改完不必重建数据源。名字与被重写的方法刻意错开，
     * 免得 `refreshAfterCacheHit()` 在类内解析到方法本身、写成无限递归。
     */
    private val shouldRefreshAfterCacheHit: () -> Boolean = { true },
    private val mapper: (response: Resp, phase: FeedLoadPhase) -> List<FeedItem>,
) : FeedSource<String> {

    override fun refreshAfterCacheHit(): Boolean = shouldRefreshAfterCacheHit()

    override suspend fun load(cursor: String?): FeedPage<String> {
        val phase = if (cursor == null) FeedLoadPhase.FirstPage else FeedLoadPhase.NextPage
        val response: Resp = if (cursor == null) {
            initialFetch()
        } else {
            replayNextUrl(pagingGson, cursor, responseClass)
        }
        val nextCursor = nextCursorOf(response)
        // 条目映射可能不便宜（转换、过滤、逐条建模），挪到后台保住 main-safe 契约
        val items = withContext(Dispatchers.Default) { mapper(response, phase) }
        // 落盘首屏：只在映射出内容时缓存（首页整页被滤空 #729 时不存无用快照，否则每次冷启都
        // 读回它、映射成空再丢弃）。fire-and-forget——落盘只服务「下次冷启」，与本次刷新的展示无关，
        // 不能让 gson 序列化 + Room 写延迟新内容上屏（虽都在后台，但会推后 load 返回→UI 更新）。
        val store = cache
        if (cursor == null && store != null && items.isNotEmpty()) {
            cacheWriteScope.launch { store.write(response, nextCursor) }
        }
        return FeedPage(items = items, nextCursor = nextCursor)
    }

    /** 本地优先：磁盘快照恢复上次首屏；无缓存 / 未命中 / 恢复后整页被滤空 / 恢复出错都返回 null。 */
    override suspend fun loadFromCache(): FeedPage<String>? {
        val store = cache ?: return null
        val cached: CachedFirstPage<Resp> = store.read() ?: return null
        return try {
            // 计时打点：与 FeedFirstPageCache.read() 的磁盘读取耗时分开看——冷启路径慢的话，
            // 得知道是 IO/反序列化慢还是 mapper（逐条 bean 转换等）慢，两者优化手段完全不同。
            val mapStartNanos = System.nanoTime()
            val items = withContext(Dispatchers.Default) {
                mapper(cached.payload, FeedLoadPhase.CacheRestore)
            }
            val mapMs = (System.nanoTime() - mapStartNanos) / 1_000_000
            Timber.d("feeds: 本地优先 mapper 映射 %d 条耗时 %dms", items.size, mapMs)
            if (items.isEmpty()) {
                null
            } else {
                FeedPage(items = items, nextCursor = cached.nextCursor?.takeIf { it.isNotEmpty() })
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Throwable) {
            // 自包含兜底：坏快照 / mapper 在 gson 还原出的边界数据上抛错，一律视为未命中，不外抛——
            // 契约「bad cache = miss」在此闭合，调用方（FeedViewModel）无需再依赖外层 try 兜这个崩溃。
            Timber.w(ex, "feed cache 恢复映射失败，视为未命中")
            null
        }
    }
}

/**
 * 纯网络 [PixivFeedSource]：`Resp` 由 [initialFetch] 的返回类型推断，类型令牌自动填。
 *
 * 用法（零捕获：先把 Fragment 属性取成局部 val 再进 lambda）：
 * ```
 * pixivFeedSource(initialFetch = { Client.appApi.getUserIllusts(userId) }) { resp, _ ->
 *     resp.displayList.mapNotNull(IllustFeedItem::from)
 * }
 * ```
 * 需要翻页门控（只出首页 / 被设置项关掉后不再翻）时传 [nextCursorOf]。
 */
inline fun <reified Resp : KListShow<*>> pixivFeedSource(
    noinline initialFetch: suspend () -> Resp,
    noinline nextCursorOf: (Resp) -> String? = { it.nextPageUrl?.takeIf(String::isNotEmpty) },
    noinline mapper: (response: Resp, phase: FeedLoadPhase) -> List<FeedItem>,
): PixivFeedSource<Resp> = PixivFeedSource(
    responseClass = Resp::class.java,
    initialFetch = initialFetch,
    nextCursorOf = nextCursorOf,
    mapper = mapper,
)

/**
 * 「本地优先」版 [PixivFeedSource] 便捷构造器：给一个稳定 [slot] 即开磁盘缓存。
 *
 * 用法（零捕获：先把 Fragment 属性取成局部 val 再进 lambda）：
 * ```
 * cachedPixivFeedSource("recmd-$apiType",
 *     initialFetch = { Client.appApi.getRecommendedWorksWithRanking(apiType) },
 * ) { resp, phase -> mapRecmdPage(resp.illusts, resp.ranking_illusts, phase, dataType) }
 * ```
 *
 * [refreshAfterCacheHit] 传 `{ false }`（或读设置项的 lambda）即「命中快照就停在快照上，
 * 不再自动刷新」，见 [FeedSource.refreshAfterCacheHit]。
 */
inline fun <reified Resp : KListShow<*>> cachedPixivFeedSource(
    slot: String,
    maxAge: Duration = DEFAULT_FEED_CACHE_MAX_AGE,
    noinline initialFetch: suspend () -> Resp,
    noinline nextCursorOf: (Resp) -> String? = { it.nextPageUrl?.takeIf(String::isNotEmpty) },
    noinline refreshAfterCacheHit: () -> Boolean = { true },
    noinline mapper: (response: Resp, phase: FeedLoadPhase) -> List<FeedItem>,
): PixivFeedSource<Resp> = PixivFeedSource(
    responseClass = Resp::class.java,
    initialFetch = initialFetch,
    cache = feedFirstPageCache(slot, Resp::class.java, maxAge),
    nextCursorOf = nextCursorOf,
    shouldRefreshAfterCacheHit = refreshAfterCacheHit,
    mapper = mapper,
)
