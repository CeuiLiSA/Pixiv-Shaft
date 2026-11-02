package ceui.pixiv.feeds.pixiv

import ceui.pixiv.api.model.KListShow
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase
import ceui.pixiv.feeds.cache.FeedCacheBackend
import ceui.pixiv.feeds.cache.FeedCacheRecord
import ceui.pixiv.feeds.cache.CachedFirstPage
import ceui.pixiv.feeds.cache.FeedFirstPageCache
import ceui.pixiv.feeds.cache.FeedFirstPageStore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PixivFeedSource] 的缓存接线（网络首屏落盘 + 缓存恢复 + 阶段传递）与翻页门控单测，
 * 注入内存假 backend，不碰网络 / Room。锁定：首屏走 FirstPage、落盘、loadFromCache 用
 * CacheRestore 恢复、映射为空的首屏不落盘、未配缓存时 loadFromCache 恒 null、
 * [PixivFeedSource.nextCursorOf] 能门控翻页。
 */
class PixivFeedSourceCacheTest {

    private data class FakeResp(
        override val displayList: List<Int>,
        override val nextPageUrl: String?,
    ) : KListShow<Int>

    private data class Row(val id: Int) : FeedItem {
        override val feedKey: Any get() = id
    }

    private class InMemoryBackend : FeedCacheBackend {
        val map = HashMap<String, FeedCacheRecord>()
        override suspend fun load(key: String): FeedCacheRecord? = map[key]
        override suspend fun save(key: String, record: FeedCacheRecord) {
            map[key] = record
        }
        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    private fun cache(backend: FeedCacheBackend) = FeedFirstPageCache(
        slot = "test",
        type = FakeResp::class.java,
        maxAgeMillis = 60_000L,
        backend = backend,
        gson = Gson(),
        accountId = { 1L },
        now = { 1_000L },
    )

    @Test
    fun `first-page network load persists snapshot and loadFromCache restores it`() = runBlocking {
        val backend = InMemoryBackend()
        val phases = mutableListOf<FeedLoadPhase>()
        // 落盘是 fire-and-forget：注入受控 scope，load 后 join 其子协程等写盘落定再断言
        val writeParent = Job()
        val source = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { FakeResp(listOf(1, 2), "next-url") },
            cache = cache(backend),
            cacheWriteScope = CoroutineScope(Dispatchers.Unconfined + writeParent),
        ) { resp, phase ->
            phases.add(phase)
            resp.displayList.map { Row(it) }
        }

        val page = source.load(null)
        writeParent.children.toList().forEach { it.join() }
        assertEquals(listOf(Row(1), Row(2)), page.items)
        assertEquals("next-url", page.nextCursor)
        assertEquals(FeedLoadPhase.FirstPage, phases.last())
        assertTrue("首屏应落盘", backend.map.isNotEmpty())

        val restored = source.loadFromCache()
        assertEquals(listOf(Row(1), Row(2)), restored?.items)
        assertEquals("next-url", restored?.nextCursor)
        assertEquals(FeedLoadPhase.CacheRestore, phases.last()) // 恢复走 CacheRestore 阶段
    }

    @Test
    fun `empty first page is not cached`() = runBlocking {
        val backend = InMemoryBackend()
        val source = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { FakeResp(emptyList(), "next-url") },
            cache = cache(backend),
        ) { resp, _ -> resp.displayList.map { Row(it) } }

        source.load(null)
        assertTrue("映射为空的首屏不应落盘", backend.map.isEmpty())
        assertNull(source.loadFromCache())
    }

    @Test
    fun `no cache configured means loadFromCache is null`() = runBlocking {
        val source = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { FakeResp(listOf(1), null) },
        ) { resp, _ -> resp.displayList.map { Row(it) } }

        source.load(null)
        assertNull(source.loadFromCache())
    }

    @Test
    fun `loadFromCache swallows a mapper crash and returns null`() = runBlocking {
        val backend = InMemoryBackend()
        val writeParent = Job()
        // mapper 只在恢复态抛错：首屏正常落盘，恢复时映射崩溃必须被自吞成 null（不外抛、不崩）
        val source = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { FakeResp(listOf(1, 2), "next-url") },
            cache = cache(backend),
            cacheWriteScope = CoroutineScope(Dispatchers.Unconfined + writeParent),
        ) { resp, phase ->
            if (phase == FeedLoadPhase.CacheRestore) throw RuntimeException("restore mapper boom")
            resp.displayList.map { Row(it) }
        }

        source.load(null)
        writeParent.children.toList().forEach { it.join() }
        assertNull(source.loadFromCache())
    }

    @Test
    fun `fresh-only side effect is suppressed on cache restore`() = runBlocking {
        val backend = InMemoryBackend()
        val writeParent = Job()
        var freshFetchSideEffects = 0
        // 模拟「拉取成功」副作用（喂画像池 / 写浏览历史）——只在真网络拉取(isFreshFetch)累加
        val source = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { FakeResp(listOf(1, 2), "next-url") },
            cache = cache(backend),
            cacheWriteScope = CoroutineScope(Dispatchers.Unconfined + writeParent),
        ) { resp, phase ->
            if (phase.isFreshFetch) freshFetchSideEffects++
            resp.displayList.map { Row(it) }
        }

        source.load(null) // FirstPage → 副作用 +1
        writeParent.children.toList().forEach { it.join() }
        assertEquals(1, freshFetchSideEffects)

        source.loadFromCache() // CacheRestore → 副作用不再触发
        assertEquals(1, freshFetchSideEffects)
    }

    @Test
    fun `default nextCursor comes from the response nextUrl and blank means end`() = runBlocking {
        val withNext = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { FakeResp(listOf(1), "next-url") },
        ) { resp, _ -> resp.displayList.map { Row(it) } }
        assertEquals("next-url", withNext.load(null).nextCursor)

        // 空串 nextUrl 与 null 同义（服务端偶发返回 ""），必须判成到底而不是拿空串去请求
        val blankNext = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { FakeResp(listOf(1), "") },
        ) { resp, _ -> resp.displayList.map { Row(it) } }
        assertNull(blankNext.load(null).nextCursor)
    }

    @Test
    fun `nextCursorOf gates paging without the source hand-rolling the protocol`() = runBlocking {
        // 「只出首页」的货架 / 被设置项关掉翻页的列表：响应照常带回 nextUrl，门控钩子返回 null
        var pagingAllowed = false
        val source = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { FakeResp(listOf(1), "next-url") },
            nextCursorOf = { resp -> if (pagingAllowed) resp.nextPageUrl else null },
        ) { resp, _ -> resp.displayList.map { Row(it) } }

        assertNull("门控关时不给游标，FeedViewModel.loadMore 直接到底", source.load(null).nextCursor)

        pagingAllowed = true
        assertEquals("门控开时照常翻页", "next-url", source.load(null).nextCursor)
    }

    /** 进程内存层（评论第一页交接）实现 [FeedFirstPageStore] 直接接进来，不经 gson / backend。 */
    private class InMemoryStore : FeedFirstPageStore<FakeResp> {
        var page: CachedFirstPage<FakeResp>? = null
        override suspend fun read(): CachedFirstPage<FakeResp>? = page
        override suspend fun write(response: FakeResp, nextCursor: String?) {
            page = CachedFirstPage(response, nextCursor, 0L)
        }
    }

    @Test
    fun `any FeedFirstPageStore works as the local-first tier`() = runBlocking {
        val store = InMemoryStore()
        val phases = mutableListOf<FeedLoadPhase>()
        var networkCalls = 0
        val writeParent = Job()
        val source = PixivFeedSource(
            responseClass = FakeResp::class.java,
            initialFetch = { networkCalls++; FakeResp(listOf(7), "next") },
            cache = store,
            cacheWriteScope = CoroutineScope(Dispatchers.Unconfined + writeParent),
        ) { resp, phase ->
            phases.add(phase)
            resp.displayList.map { Row(it) }
        }

        // 另一页预先交接过来的快照：冷启恢复不打网络
        store.write(FakeResp(listOf(1, 2), "handed-over"), "handed-over")
        val restored = source.loadFromCache()
        assertEquals(listOf(Row(1), Row(2)), restored?.items)
        assertEquals("handed-over", restored?.nextCursor)
        assertEquals(FeedLoadPhase.CacheRestore, phases.last())
        assertEquals(0, networkCalls)

        // 网络首屏照常写回同一个 store
        val page = source.load(null)
        writeParent.children.toList().forEach { it.join() }
        assertEquals(listOf(Row(7)), page.items)
        assertEquals(listOf(7), store.page?.payload?.displayList)
        assertEquals("next", store.page?.nextCursor)
    }
}
