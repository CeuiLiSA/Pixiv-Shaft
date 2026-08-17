package ceui.pixiv.feeds

/**
 * 一页数据：条目 + 下一页游标。
 *
 * [nextCursor] 为 null 表示已经到底。
 */
data class FeedPage<Cursor : Any>(
    val items: List<FeedItem>,
    val nextCursor: Cursor?,
)

/**
 * 数据源契约：一个必做的 suspend [load]，外加一个可选的本地优先能力 [loadFromCache]。
 *
 * - `cursor == null` 表示加载第一页（首屏 / 刷新都会走这里）；否则加载游标指向的下一页。
 *
 * 设计取舍：
 * - 仍是 `fun interface`：只有 [load] 一个抽象方法，最简单的数据源可直接写成
 *   `FeedSource { cursor -> ... }`；[loadFromCache] 有默认实现，不算抽象方法，不破坏 SAM。
 * - 本地优先做成**带默认实现的可选能力**而不是独立的标记子接口：调用方（[FeedViewModel]）
 *   直接 `source.loadFromCache()` 即可，不必 `as?` 运行时探测能力、也就没有 `@Suppress`
 *   的非受检泛型转换——绝大多数源无需实现，默认返回 null 即「无缓存」。
 * - 游标是泛型：pixiv 的 nextUrl 是 String，本地库可以用页码 Int 或 Room 的 offset；
 * - 实现必须 main-safe（Retrofit suspend 天然满足；自己做重 IO / 解析要切 Dispatchers）。
 *
 * 抛出的异常由 [FeedViewModel] 统一转成 [LoadState.Error]，实现内不要自行吞错。
 */
fun interface FeedSource<Cursor : Any> {

    suspend fun load(cursor: Cursor?): FeedPage<Cursor>

    /**
     * 本地优先（可选）：冷启时从磁盘快照即时恢复上一次首屏；无缓存 / 未命中 / 损坏都返回 null。
     *
     * 默认无缓存（返回 null）——[FeedViewModel] 冷启会先调它秒显旧首屏，再照常网络刷新覆盖
     * （RemoteMediator 语义：本地兜首屏，翻页仍走网络）。实现见 `:app` 的 `PixivFeedSource`。
     *
     * - 必须 main-safe（重 IO / 反序列化自行切线程）；不碰网络；
     * - 不产生「拉取成功」类副作用（喂画像池、写浏览历史等只属于真正的网络 [load]，拿旧数据
     *   重放会污染下游——用 [FeedLoadPhase.CacheRestore] 区分）。
     */
    suspend fun loadFromCache(): FeedPage<Cursor>? = null

    /**
     * 冷启命中磁盘快照之后，是否还要立刻发一次网络刷新覆盖它。默认 true（RemoteMediator 语义）。
     *
     * 返回 false 就停在快照上，由用户下拉刷新才拉新内容——「启动时不自动刷新首页推荐」这类
     * 用户设置（issue #955）用它表达：推荐流每次冷启换一整批内容，会让上次没看完的作品直接消失。
     *
     * 只在**命中缓存**时被问到：未命中时屏幕上没有内容可停留，仍然必须走网络。
     * 每次 [FeedViewModel.refresh] 现读（不是构造期快照），设置改完下次冷启即生效。
     *
     * ⚠️ 契约：必须是廉价的纯内存判断（读设置项 / 常量），**不要碰 IO、不要抛**。故意做成非
     * suspend 就是为了表达这一点。它在 [FeedViewModel.refresh] 的网络 try 之外、主线程上被调用，
     * 抛出来会经 viewModelScope 直奔线程默认处理器崩掉进程；要读磁盘请改用 [loadFromCache]。
     */
    fun refreshAfterCacheHit(): Boolean = true
}
