package ceui.pixiv.ui.comments

import ceui.pixiv.api.model.CommentResponse
import ceui.pixiv.feeds.cache.CachedFirstPage
import ceui.pixiv.feeds.cache.FeedFirstPageStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 评论第一页的进程内一级缓存。
 *
 * 详情页评论预览（[ceui.pixiv.ui.detail.fetchArtworkComments]）拉到整页（约 30 条 + next_url）
 * 却只画前 3 条，紧接着点「查看更多」进 [CommentsFragment] 又为同一页再发一次请求。现在预览
 * 把整页 [put] 进来，列表页的 feed 源经 [storeFor] 以「本地优先」通道接走：冷启命中即终态
 * （[ceui.pixiv.feeds.FeedSource.refreshAfterCacheHit] 返回 false，不再补发网络），下拉刷新
 * 走的是 `load(null)`，照常打网络。列表页自己的网络首屏也会写回来，[DEFAULT_MAX_AGE] 内
 * 再进同一作品的评论页同样免请求。
 *
 * 一致性：本 app 自己发 / 删评论后由 [CommentsComposerViewModel] 调 [invalidate]，下一次进
 * 列表页拿到的是服务端的新第一页；别人的新评论靠 [DEFAULT_MAX_AGE] 到期 + 下拉刷新。
 *
 * 已知边界：框架把「映射为空」的快照一律当未命中（[ceui.pixiv.feeds.FeedViewModel.refresh]
 * 只采纳非空快照，这是磁盘缓存防 #729 空页闪现的规则，这里不去动它），所以零评论的作品进
 * 列表页仍会再打一次网络——代价是一个空响应，可接受。
 *
 * 线程：详情页在 IO 线程写、列表页在主线程读，[entries] 全程 `synchronized`。
 * 容量 [DEFAULT_MAX_ENTRIES] 条 LRU；每条一页评论，几十 KB 量级。
 */
class CommentsFirstPageCache(
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE.inWholeMilliseconds,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val entries = object : LinkedHashMap<CommentTarget, CachedFirstPage<CommentResponse>>(
        16, 0.75f, /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CommentTarget, CachedFirstPage<CommentResponse>>,
        ): Boolean = size > maxEntries
    }

    /** 存下 [target] 的第一页；[nextCursor] 默认取响应自带的 next_url。 */
    fun put(
        target: CommentTarget,
        response: CommentResponse,
        nextCursor: String? = response.next_url?.takeIf { it.isNotEmpty() },
    ) {
        val page = CachedFirstPage(response, nextCursor, now())
        synchronized(entries) { entries[target] = page }
    }

    /** 未存过 / 已过期（顺手清掉）都返回 null。 */
    fun get(target: CommentTarget): CachedFirstPage<CommentResponse>? = synchronized(entries) {
        val page = entries[target] ?: return null
        if (now() - page.savedAtMillis > maxAgeMillis) {
            entries.remove(target)
            null
        } else {
            page
        }
    }

    /** 本 app 自己改了这个对象的评论（发 / 删）之后调：下一次进列表页必须拿服务端的新第一页。 */
    fun invalidate(target: CommentTarget) {
        synchronized(entries) { entries.remove(target) }
    }

    /** 给 [target] 的 feed 源用的存取句柄（零捕获：只持有 target 与本缓存）。 */
    fun storeFor(target: CommentTarget): FeedFirstPageStore<CommentResponse> =
        object : FeedFirstPageStore<CommentResponse> {
            override suspend fun read(): CachedFirstPage<CommentResponse>? = get(target)
            override suspend fun write(response: CommentResponse, nextCursor: String?) =
                put(target, response, nextCursor)
        }

    companion object {
        /** 超过多旧就不再拿来当第一页：评论变动不频繁，5 分钟内详情页预览与列表页看到同一页是合理预期。 */
        val DEFAULT_MAX_AGE: Duration = 5.minutes

        /** 同时保留几个对象的第一页：详情页来回点几个作品足够，再多没意义。 */
        const val DEFAULT_MAX_ENTRIES: Int = 8

        /** 进程级共享实例：详情页写、评论列表页读、发 / 删评论时失效。 */
        val shared: CommentsFirstPageCache = CommentsFirstPageCache()
    }
}
